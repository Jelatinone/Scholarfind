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
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.jelatinone.scholarfind.json.JsonHandler;
import com.github.jelatinone.scholarfind.json.JsonSerializer;
import com.github.jelatinone.scholarfind.meta.State;
import com.github.jelatinone.scholarfind.meta.Task;
import com.github.jelatinone.scholarfind.models.AnnotateDocument;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletion.Choice;
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
	static JsonSerializer<AnnotateDocument> _serializer = (generator, document) -> {
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
	};

	@NonFinal
	WebClient client;
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
		super("annotate");
		setMessage("Initialization started");
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

			AgentType agentType = command.getParsedOptionValue("agent");
			if (agentType == null) {
				String message = "Initialization failed : Failed to retrieve agent type";
				setMessage(message);
				_logger.severe(String.format("%s [%s] :: %s", getName(), getState(), message));
				setState(State.FAILED);
				return;
			}
			agent = agentType;

			client = new WebClient(BrowserVersion.BEST_SUPPORTED);
			handler = JsonHandler.acquireWriter(destination, _serializer);

			configureClient();
		} catch (final ParseException exception) {
			String message = String.format("Initialization failed : Failed to parse arguments %s", exception.getMessage());
			setMessage(message);
			_logger.severe(String.format("%s [%s] :: %s", getName(), getState(), message));
			setState(State.FAILED);
			return;
		} catch (final IOException exception) {
			String message = String.format("Initialization failed : Failed to create JSON handler", exception.getMessage());
			setMessage(message);
			_logger.severe(String.format("%s [%s] :: %s", getName(), getState(), message));
			setState(State.FAILED);
			return;
		}
		setMessage("Initialization complete");
	}

	protected synchronized List<URL> collect() {
		setMessage("Collection started");

		File file = new File(source);
		ObjectMapper mapper = new ObjectMapper();

		List<URL> items = new ArrayList<>();
		try {
			setMessage("Collection acquired");
			ArrayNode results = JsonHandler.acquireContent(file, mapper);
			for (final JsonNode entry : results) {
				ArrayNode retrieved = (ArrayNode) entry.get("retrieved");
				retrieved.forEach((data) -> {
					String text = data.textValue();
					try {
						URL url = URI.create(text).toURL();
						items.add(url);
					} catch (final MalformedURLException | IllegalArgumentException exception) {
						String message = String.format("Skipped malformed URL : %s", text);
						setMessage(message);
						_logger.severe(String.format("%s [%s] :: %s", getName(), getState(), message));
					}
				});
			}
			setMessage(String.format("Found %d URLs", items.size()));
		} catch (final IOException exception) {
			String message = String.format("Failed to retrieve source content: %s", source);
			setMessage(message);
			_logger.severe(String.format("%s [%s] :: %s", getName(), getState(), exception.getMessage()));
			setState(State.FAILED);
		}
		setMessage("Collection complete");
		return items;
	}

	private void configureClient() {
		client
				.getOptions()
				.setDownloadImages(false);
		client
				.getOptions()
				.setTimeout(timeout);
		client
				.getOptions()
				.setCssEnabled(false);
		client
				.getOptions()
				.setJavaScriptEnabled(false);
		client
				.getOptions()
				.setThrowExceptionOnScriptError(false);
		client
				.getOptions()
				.setPrintContentOnFailingStatusCode(false);
		client
				.getOptions()
				.setThrowExceptionOnFailingStatusCode(true);
	}

	@Override
	public synchronized void close() throws IOException {
		setMessage("Closing resources");
		try {
			client.close();
			handler.close();
		} catch (final IOException exception) {
			String message = "Closing resources failed";
			setMessage(message);
			_logger.severe(String.format("%s [%s] :: %s", message));
			throw exception;
		}
		setMessage("Close resources safely completed");
	}

	@Override
	protected synchronized AnnotateDocument operate(final @NonNull URL operand) {
		try {
			setMessage("Retrieving page content");
			final HtmlPage pageContent = client
					.<HtmlPage>getPage(operand);

			setMessage("Annotating content");
			AnnotateDocument document;
			try {
				document = agent.annotate(pageContent);
			} catch (final Exception exception) {
				document = null;

				String message = String.format("Agent failed to annotate document : %s", exception.getMessage());
				setMessage(message);
				_logger.fine(String.format("%s [%s] :: %s", getName(), getState(),
						message));
			}
			if (document == null) {
				String message = String.format("Agent failed to create document", operand.toString());
				setMessage(message);
				_logger.fine(String.format("%s [%s] :: %s", getName(), getState(),
						message));
			}
			return document;
		} catch (final FailingHttpStatusCodeException exception) {
			String message = String.format("Failing status code returned URL : %s", operand.toString());
			setMessage(message);
			_logger.severe(String.format("%s [%s] :: %s", getName(), getState(),
					message));
		} catch (final IOException exception) {
			String message = String.format("Failed to retrieve content from URL : %s", operand.toString());
			setMessage(message);
			_logger.severe(String.format("%s [%s] :: %s", getName(), getState(),
					message));
		}
		setState(State.FAILED);
		return null;
	}

	@Override
	protected synchronized boolean result(final AnnotateDocument operand) {
		if (operand != null) {
			try {
				handler.writeDocument(operand);
				return true;
			} catch (final IOException ignored) {
				String message = String.format("Failed to write annotate document : %s", operand.domain().toString());
				setMessage(message);
				_logger.severe(String.format("%s [%s] :: %s", message));
				return false;
			}
		}
		String message = "Failed to write annotate document: operand was null";
		setMessage(message);
		_logger.severe(String.format("%s [%s] :: %s", getName(), getState(), message));
		return false;
	}

	@Override
	protected synchronized void restart() throws IOException {
		setMessage("Restarting resources");
		try {
			client.close();
			client = new WebClient(BrowserVersion.BEST_SUPPORTED);
			configureClient();

			handler.close();
			handler = JsonHandler.acquireWriter(destination, _serializer);
		} catch (final IOException exception) {
			String message = "Restarting resources safely failed";
			setMessage(message);
			_logger.severe(String.format("%s [%s] :: %s", message));
			throw exception;
		}
		setMessage("Restarting resources safely completed");
	}

	static enum AgentType {
		CHAT_GPT((content) -> {
			String normalPage = content.asNormalizedText();
			OpenAIClient client = OpenAIOkHttpClient.builder()
					.apiKey(System.getenv("OPENAI_API_KEY"))
					.build();
			StructuredChatCompletionCreateParams<AnnotateDocument> params = ChatCompletionCreateParams.builder()
					.addSystemMessage(DEFAULT_AGENT_PROMPT)
					.addUserMessage(normalPage)
					.model(ChatModel.GPT_4O_MINI)
					.responseFormat(AnnotateDocument.class)
					.build();
			StructuredChatCompletion<AnnotateDocument> completion = client.chat()
					.completions()
					.create(params);
			Choice<AnnotateDocument> choice = completion.choices().get(0);
			AnnotateDocument document = choice.message()
					.content()
					.orElse(null);

			return document;
		});

		AgentAnnotator annotator;

		private AgentType(final AgentAnnotator annotator) {
			this.annotator = annotator;
		}

		public AnnotateDocument annotate(final @NonNull HtmlPage content) {
			return annotator.annotate(content);
		}
	}

	@FunctionalInterface
	static interface AgentAnnotator {

		AnnotateDocument annotate(final @NonNull HtmlPage content);
	}
}
