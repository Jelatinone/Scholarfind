package com.github.jelatinone.scholarfind.tasks;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
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
import com.github.jelatinone.scholarfind.agent.AgentHandler;
import com.github.jelatinone.scholarfind.json.JsonHandler;
import com.github.jelatinone.scholarfind.json.JsonSerializer;
import com.github.jelatinone.scholarfind.meta.State;
import com.github.jelatinone.scholarfind.meta.Task;
import com.github.jelatinone.scholarfind.models.AgentType;
import com.github.jelatinone.scholarfind.models.AnnotateDocument;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.AnnotateStub;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.EducationLevel;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.Supplement;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.PursuedDegreeLevel;
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
		final AnnotateStub stub = document.stub();

		generator.writeStartObject();

		generator.writeStringField("name", stub.scholarshipTitle());
		generator.writeStringField("url", document.domain().toString());

		if (stub.award() != null) {
			generator.writeNumberField("award", stub.award());
		} else {
			generator.writeNullField("award");
		}

		generator.writeStringField(
				"open",
				stub.open().toString());
		generator.writeStringField(
				"close",
				stub.close().toString());

		generator.writeFieldName("pursued");
		generator.writeStartArray();
		for (PursuedDegreeLevel value : stub.pursued()) {
			generator.writeString(value.name());
		}
		generator.writeEndArray();

		generator.writeFieldName("education");
		generator.writeStartArray();
		for (EducationLevel value : stub.education()) {
			generator.writeString(value.name());
		}
		generator.writeEndArray();

		generator.writeFieldName("supplements");
		generator.writeStartArray();
		for (Supplement value : stub.supplements()) {
			generator.writeString(value.name());
		}
		generator.writeEndArray();

		generator.writeFieldName("requirements");
		generator.writeStartArray();
		for (String value : stub.requirements()) {
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
	AgentHandler<AnnotateStub> agent;

	@NonFinal
	String source;
	@NonFinal
	String destination;
	@NonFinal
	Integer timeout;
	@NonFinal
	AgentType type;

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
		withMessage("Initialization started", Level.INFO);
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
				withMessage(message, Level.SEVERE);
				withState(State.FAILED);
				return;
			}
			type = agentType;
			agent = type.acquire(DEFAULT_AGENT_PROMPT, AnnotateStub.class);

			client = new WebClient(BrowserVersion.BEST_SUPPORTED);
			handler = JsonHandler.acquireWriter(destination, _serializer);

			configureClient();
		} catch (final ParseException exception) {
			String message = String.format("Initialization failed : Failed to parse arguments %s",
					exception.getMessage());
			withMessage(message, Level.SEVERE);
			withState(State.FAILED);
			return;
		} catch (final IOException exception) {
			String message = String.format("Initialization failed : Failed to create JSON handler",
					exception.getMessage());
			withMessage(message, Level.SEVERE);
			withState(State.FAILED);
			return;
		}
		withMessage("Initialization complete", Level.INFO);
	}

	protected synchronized List<URL> collect() {
		withMessage("Collection started", Level.INFO);

		File file = new File(source);
		ObjectMapper mapper = new ObjectMapper();

		List<URL> items = new ArrayList<>();
		try {
			withMessage("Collection acquired", Level.INFO);
			ArrayNode results = JsonHandler.acquireContent(file, mapper);
			for (final JsonNode entry : results) {
				ArrayNode retrieved = (ArrayNode) entry.get("retrieved");
				retrieved.forEach((data) -> {
					String text = data.textValue();
					try {
						URL url = URI.create(text).toURL();
						items.add(url);

						String message = String.format("Added valid URL : %s", text);
						withMessage(message, Level.SEVERE);						
					} catch (final MalformedURLException | IllegalArgumentException exception) {
						String message = String.format("Skipped malformed URL : %s", text);
						withMessage(message, Level.SEVERE);
					}
				});
			}
			withMessage(String.format("Found %d URLs", items.size()), Level.INFO);
		} catch (final IOException exception) {
			String message = String.format("Failed to retrieve source content: %s", source);
			withMessage(message, Level.SEVERE);
			withState(State.FAILED);
		}
		withMessage("Collection complete", Level.INFO);
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
		withMessage("Closing resources", Level.INFO);
		try {
			client.close();
			agent.close();
			handler.close();
		} catch (final IOException exception) {
			String message = "Closing resources failed";
			withMessage(message, Level.SEVERE);
			throw exception;
		}
		withMessage("Close resources safely completed", Level.INFO);
	}

	@Override
	protected synchronized AnnotateDocument operate(final @NonNull URL operand) {
		String urlString = operand.toString();
		try {

			withMessage(String.format("Retrieving page content : %s", urlString), Level.INFO);
			final HtmlPage pageContent = client
					.<HtmlPage>getPage(operand);

			withMessage(String.format("Annotating content : %s", urlString), Level.INFO);
			AnnotateDocument document;
			try {
				String text = pageContent.getVisibleText();
				AnnotateStub stub = agent.annotate(text);
				document = new AnnotateDocument(operand, stub);
			} catch (final Exception exception) {
				document = null;
				String message = String.format("Agent failed to annotate document : %s", exception.getMessage());
				withMessage(message, Level.SEVERE);
			}
			if (document == null) {
				String message = String.format("Agent failed to create document : %s", operand.toString());
				withMessage(message, Level.SEVERE);
			}
			return document;
		} catch (final FailingHttpStatusCodeException exception) {
			String message = String.format("Failing status code returned URL : %s", urlString);
			withMessage(message, Level.SEVERE);
		} catch (final IOException exception) {
			String message = String.format("Failed to retrieve content from URL : %s", urlString);
			withMessage(message, Level.SEVERE);
		}
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
				withMessage(message, Level.SEVERE);
				return false;
			}
		}
		String message = "Failed to write annotate document: operand was null";
		withMessage(message, Level.SEVERE);
		return false;
	}

	@Override
	protected synchronized void restart() throws IOException {
		withMessage("Restarting resources", Level.INFO);
		try {
			client.close();
			client = new WebClient(BrowserVersion.BEST_SUPPORTED);
			configureClient();

			agent.close();
			agent = type.acquire(DEFAULT_AGENT_PROMPT, AnnotateStub.class);

			handler.close();
			handler = JsonHandler.acquireWriter(destination, _serializer);
		} catch (final IOException exception) {
			String message = "Restarting resources safely failed";
			withMessage(message, Level.SEVERE);
			throw exception;
		}
		withMessage("Restarting resources safely completed", Level.INFO);
	}
}
