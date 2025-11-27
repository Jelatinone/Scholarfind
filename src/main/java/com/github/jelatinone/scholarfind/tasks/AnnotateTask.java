package com.github.jelatinone.scholarfind.tasks;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.jelatinone.scholarfind.json.JsonHandler;
import com.github.jelatinone.scholarfind.meta.State;
import com.github.jelatinone.scholarfind.meta.Task;
import com.github.jelatinone.scholarfind.models.AnnotateDocument;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public final class AnnotateTask extends Task<URL, AnnotateDocument> {
	static String DEFAULT_AGENT_PROMPT = """
			Map all relevant scholarship information into the following JSON schema.
			All date fields must use ISO-8601 format (YYYY-MM-DD).
			If a value cannot be determined, set it to null.

			{
			    name: <the full name of the scholarship>,
			    domain: <the URL domain where the scholarship is located>,
			    award: <numeric award amount as an double; if not found or non-numeric, use -1>,
			    open: <the scholarship's opening date in ISO format (YYYY-MM-DD), or null>,
			    close: <the scholarship's closing date in ISO format (YYYY-MM-DD), or null>,
			    supplement: <an array of all additional supplements required, such as Letters of Recommendation, Essays, Statements, Portfolios>,
			    requirements: <an array of all eligibility requirements, such as GPA, age, grade level, financial need, demographic qualifiers, field of study, citizenship status>
			}

			Return only valid JSON matching this structure exactly.
			""";
	static Options _config = new Options();
	static Logger _logger = Logger.getLogger(AnnotateTask.class.getName());
	static CommandLineParser _parser = new DefaultParser();

	@NonFinal
	JsonHandler<AnnotateDocument> handler;
	@NonFinal
	String source;
	@NonFinal
	String destination;
	@NonFinal
	Integer timeout;
	@NonFinal
	AgentType agent;

	static {
		_config.addOptions(DEFAULT_OPTION_CONFIGURATION);
		Option opt_networkTimeout = Option.builder()
				.longOpt("timeout")
				.numberOfArgs(1)
				.hasArg()
				.valueSeparator('=')
				.desc("maximum time (seconds) to wait for a network request")
				.converter(Integer::valueOf)
				.get();
		_config.addOption(opt_networkTimeout);
		Option opt_agentType = Option.builder()
				.longOpt("agent")
				.numberOfArgs(1)
				.hasArg()
				.valueSeparator('=')
				.desc("type of agent to execute this task with")
				.converter((value) -> AgentType.valueOf(value.toUpperCase()))
				.get();
		_config.addOption(opt_agentType);
	}

	public AnnotateTask(final @NonNull String... arguments) {
		super("Annotate");
		setMessage("Initializing");
		try {
			CommandLine command = _parser.parse(_config, arguments);
			String sourceTarget = command.getOptionValue("from");
			source = sourceTarget;

			String destinationTarget = command.getOptionValue("to");
			destination = destinationTarget != null ? destinationTarget
					: String.format(DEFAULT_DESTINATION_LOCATION, getName(), LocalDate
							.now()
							.toString());

			Integer networkTimeout = command.getParsedOptionValue("timeout");
			timeout = networkTimeout != null ? networkTimeout : DEFAULT_NETWORK_TIMEOUT;

			handler = JsonHandler.acquireWriter(destination, (generator, document) -> {
				generator.writeStartObject();

				generator.writeStringField("name", document.name());
				generator.writeObjectField("url", document.domain().toString());
				generator.writeNumberField("award", document.award().doubleValue());

				generator.writeStringField(
						"open",
						document.open().toString());
				generator.writeStringField(
						"close",
						document.close().toString());

				generator.writeFieldName("supplement");
				for (String value : document.supplements()) {
					generator.writeString(value);
				}

				generator.writeFieldName("requirement");
				for (String value : document.requirements()) {
					generator.writeString(value);
				}
				generator.writeEndObject();
			});
		} catch (final ParseException exception) {
			setState(State.FAILED);
			_logger.severe(String.format("%s [%s] :: Failed to parse arguments %s", getName(), getState()));
			setMessage("Initialization Failed!");
			return;
		} catch (final IOException exception) {
			setState(State.FAILED);
			_logger.severe(String.format("%s [%s] :: Failed to create JSON writer %s", getName(), getState()));
			setMessage("Initialization Failed!");
			return;
		}
		setMessage("Initialization Complete");
	}

	protected synchronized List<URL> collect() {
		File file = new File(source);
		ObjectMapper mapper = new ObjectMapper();
		try {
			List<URL> items = new ArrayList<>();
			ArrayNode content = JsonHandler.acquireContent(file, mapper);
			content.forEach((item) -> {
				String text = item.textValue();
				try {
					URL url = URI.create(text).toURL();
					items.add(url);
				} catch (final MalformedURLException exception) {
					String message = String.format("%s [%s] :: Skipped malformed URL : %s", getName(), getState(), text);
					_logger.severe(message);
				}

			});
			return items;
		} catch (IOException e) {
			setState(State.FAILED);
			String message = String.format("%s [%s] :: Failed to retrieve source content", getName(), getState());
			_logger.severe(message);
			return List.of();
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (handler != null) {
			handler.close();
		}
		setMessage("Resource closed");

	}

	@Override
	protected synchronized AnnotateDocument operate(final @NonNull URL operand) {
		try (WebClient Client = new WebClient(BrowserVersion.BEST_SUPPORTED)) {
			setMessage("Configuring Collection");
			Client
					.getOptions()
					.setDownloadImages(false);
			Client
					.getOptions()
					.setTimeout(timeout);
			Client
					.getOptions()
					.setCssEnabled(false);
			Client
					.getOptions()
					.setJavaScriptEnabled(false);
			Client
					.getOptions()
					.setThrowExceptionOnScriptError(false);
			Client
					.getOptions()
					.setPrintContentOnFailingStatusCode(false);
			setMessage("Retrieving page content");
			final HtmlPage pageContent = Client
					.<HtmlPage>getPage(operand);
			setMessage("Annotating Document");
			AnnotateDocument document = agent.annotate(pageContent);
			if (document == null) {
				setState(State.FAILED);
				String message = String.format("%s [%s] :: Failed to annotate source content", getName(), getState());
				setMessage("Annotating Failed");
				_logger.severe(message);
				return null;
			}
			return document;
		} catch (final IOException exception) {
			setState(State.FAILED);
			String message = String.format("%s [%s] :: Failed to retrieve source content", getName(), getState());
			_logger.severe(message);
			return null;
		}

	}

	@Override
	protected synchronized boolean result(AnnotateDocument operand) {
		if (operand != null) {
			try {
				handler.writeDocument(operand);
				return true;
			} catch (final IOException ignored) {
			}
		}
		String message = String.format("%s [%s] :: Failed to write annotate document",
				getName(), getState());
		_logger.severe(message);
		return false;
	}

	@Override
	protected synchronized void restart() {
		setMessage("Restarting");
	}

	static enum AgentType {
		CHAT_GPT((content) -> {
			String normalizedPage = content.asNormalizedText();
			OpenAIClient client = OpenAIOkHttpClient.fromEnv();
			StructuredChatCompletionCreateParams<AnnotateDocument> params = ChatCompletionCreateParams.builder()
					.addSystemMessage(DEFAULT_AGENT_PROMPT)
					.addUserMessage(normalizedPage)
					.model(ChatModel.GPT_4O_MINI)
					.responseFormat(AnnotateDocument.class)
					.build();
			var completion = client.chat().completions().create(params);
			var choice = completion.choices().get(0);
			AnnotateDocument document = choice.message().content().orElse(null);
			return document;
		});

		AgentAnnotator annotator;

		private AgentType(final AgentAnnotator annotator) {
			this.annotator = annotator;
		}

		public AnnotateDocument annotate(final HtmlPage content) {
			return annotate(content);
		}
	}

	@FunctionalInterface
	static interface AgentAnnotator {

		AnnotateDocument annotate(final @NonNull HtmlPage content);
	}
}
