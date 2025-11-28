package com.github.jelatinone.scholarfind.tasks;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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
import com.github.jelatinone.scholarfind.models.AnnotateDocument.EducationLevel;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.Supplement;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.PursuedDegreeLevel;
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
			Extract all relevant scholarship information from the provided webpage content and map it into the following JSON schema.
			All date fields MUST use ISO-8601 format (YYYY-MM-DD).
			If a value cannot be determined, set it to null.
			Arrays MUST contain only valid enum values or strings as defined.

			{
			    scholarshipTitle: <the full official name of the scholarship>,
			    organizationName: <the organization, institution, company, funder, or sponsor offering the scholarship>,
			    award: <numeric award amount as a double; if not found or not numeric, use null>,
			    open: <the scholarship's opening date in ISO format (YYYY-MM-DD), or null>,
			    close: <the scholarship's closing date in ISO format (YYYY-MM-DD), or null>,
			    pursued: <an array of PursuedDegreeLevel enum values representing eligible degree levels>,
			    education: <an array of EducationLevel enum values representing required education levels>,
			    supplements: <an array of Supplement enum values representing additional required materials>,
			    requirements: <an array of strings describing all eligibility requirements not captured above>
			}

			Rules:
			- "pursued" must use ONLY values from the PursuedDegreeLevel enum.
			- "education" must use ONLY values from the EducationLevel enum.
			- "supplements" must use ONLY values from the Supplement enum.
			- "requirements" should capture ANY remaining textual requirements (GPA, age, citizenship, major, demographic qualifiers, financial need, etc.).
			- Never invent information. If the page does not explicitly provide it, return null or an empty array.

			Return ONLY valid JSON that matches this structure exactly.
			""";

	static Options _config = new Options();
	static Logger _logger = Logger.getLogger(AnnotateTask.class.getName());
	static CommandLineParser _parser = new DefaultParser();
	static JsonSerializer<AnnotateDocument> _serializer = (generator, document) -> {
		generator.writeStartObject();

		generator.writeStringField("name", document.scholarshipTitle());
		generator.writeStringField("url", document.domain().toString());
		generator.writeNumberField("award", document.award());

		generator.writeStringField(
				"open",
				document.open().toString());
		generator.writeStringField(
				"close",
				document.close().toString());

		generator.writeFieldName("supplements");
		generator.writeStartArray();
		for (Supplement value : document.supplements()) {
			generator.writeString(value.name());
		}
		generator.writeEndArray();

		generator.writeFieldName("requirements");
		generator.writeStartArray();
		for (String value : document.requirements()) {
			generator.writeString(value);
		}
		generator.writeEndArray();

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
			agent.annotator.open();

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
			agent.annotator.close();
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
			} catch (final IOException exception) {
				String message = String.format("Failed to write annotate document : %s", exception.getMessage());
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

			agent.annotator.close();
			agent.annotator.open();

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
		CHAT_GPT(new AgentAnnotator() {

			OpenAIClient client;

			@Override
			public void open() throws IOException {
				client = OpenAIOkHttpClient.builder()
						.apiKey(System.getenv("OPENAI_API_KEY"))
						.build();
			}

			@Override
			public void close() throws IOException {
				client.close();
			}

			@Override
			public AnnotateStub annotate(@NonNull HtmlPage content) {
				String normalPage = content.asNormalizedText();
				StructuredChatCompletionCreateParams<AnnotateStub> params = ChatCompletionCreateParams.builder()
						.addSystemMessage(DEFAULT_AGENT_PROMPT)
						.addUserMessage(normalPage)
						.model(ChatModel.GPT_4O_MINI)
						.responseFormat(
								AnnotateStub.class)
						.build();
				StructuredChatCompletion<AnnotateStub> completion = client.chat()
						.completions()
						.create(params);
				Choice<AnnotateStub> choice = completion.choices().get(0);
				AnnotateStub annotation = choice.message()
						.content()
						.orElse(null);
				return annotation;
			}
		});

		AgentAnnotator annotator;

		private AgentType(final AgentAnnotator annotator) {
			this.annotator = annotator;
		}

		public AnnotateDocument annotate(final @NonNull HtmlPage content) {
			AnnotateStub stub = annotator.annotate(content);
			AnnotateDocument document = new AnnotateDocument(
					stub.scholarshipTitle(),
					stub.organizationName(),
					content.getUrl(),
					stub.award(),
					stub.open(),
					stub.close(),
					stub.pursued(),
					stub.education(),
					stub.supplements(),
					stub.requirements());
			return document;
		}
	}

	static interface AgentAnnotator extends Closeable {

		void open() throws IOException;

		void close() throws IOException;

		AnnotateStub annotate(final @NonNull HtmlPage content);

	}

	static record AnnotateStub(
			String scholarshipTitle,
			String organizationName,
			Double award,
			LocalDate open,
			LocalDate close,
			Collection<PursuedDegreeLevel> pursued,
			Collection<EducationLevel> education,
			Collection<Supplement> supplements,
			Collection<String> requirements) {
	}
}
