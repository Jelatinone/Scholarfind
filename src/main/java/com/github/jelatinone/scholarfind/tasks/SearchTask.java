package com.github.jelatinone.scholarfind.tasks;

import com.github.jelatinone.scholarfind.json.JsonHandler;
import com.github.jelatinone.scholarfind.json.JsonSerializer;
import com.github.jelatinone.scholarfind.meta.State;
import com.github.jelatinone.scholarfind.meta.Task;
import com.github.jelatinone.scholarfind.models.SearchDocument;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.w3c.dom.Node;

/**
 * 
 * <h1>SearchTask</h1>
 * 
 * <p>
 * Performs a simple search operation on a given source location.
 * </p>
 * 
 * <p>
 * This search operation collects all available anchor tags from the parent page
 * and performs a transformation on them before outputting the resulting JSON.
 * </p>
 * 
 * 
 * @author Cody Washington
 */
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public final class SearchTask extends Task<DomNode, URL> {
	static Options _config = new Options();
	static Logger _logger = Logger.getLogger(SearchTask.class.getName());
	static CommandLineParser _parser = new DefaultParser();
	static JsonSerializer<SearchDocument> _serializer = (generator, document) -> {
		generator.writeStartObject();
		generator.writeStringField("source", document.source());
		generator.writeStringField("date", document.date());
		generator.writeStringField("time", document.time());

		generator.writeFieldName("retrieved");
		generator.writeStartArray();
		for (URL value : document.retrieved()) {
			generator.writeString(value.toString());
		}
		generator.writeEndArray();

		generator.writeEndObject();
	};

	List<URL> retrieved = new ArrayList<>();

	@NonFinal
	JsonHandler<SearchDocument> handler;
	@NonFinal
	HtmlPage pageContent;

	@NonFinal
	String source;
	@NonFinal
	String destination;
	@NonFinal
	Integer timeout;

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
	}

	/**
	 * Search Task Constructor.
	 * 
	 * @param arguments Command line arguments to parse and configure this task with
	 */
	public SearchTask(final @NonNull String... arguments) {
		super("search");
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

			handler = JsonHandler.acquireWriter(destination, _serializer);
		} catch (final ParseException exception) {
			withState(State.FAILED);
			String message = String.format("Initialization failed : Failed to parse arguments %s",
					exception.getMessage());
			withMessage(message, Level.SEVERE);
			return;
		} catch (final IOException exception) {
			withState(State.FAILED);
			String message = String.format("Initialization failed : Failed to create JSON handler %s",
					exception.getMessage());
			withMessage(message, Level.SEVERE);
			return;
		}
		withMessage("Initialization complete", Level.INFO);
	}

	protected synchronized List<DomNode> collect() {
		withMessage("Collection started", Level.INFO);
		try (WebClient Client = new WebClient(BrowserVersion.BEST_SUPPORTED)) {
			withMessage("Collection configuring", Level.INFO);
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
			withMessage("Retrieving page content", Level.INFO);
			pageContent = Client
					.<HtmlPage>getPage(source);
			withMessage("Retrieving page anchor tags", Level.INFO);
			final List<DomNode> pageAnchors = pageContent.querySelectorAll(("a"))
					.stream()
					.filter(DomNode::hasAttributes)
					.filter(Objects::nonNull)
					.toList();

			withMessage(String.format("Found %d anchors", pageAnchors.size()), Level.INFO);
			return pageAnchors;
		} catch (final IOException exception) {
			String message = String.format("Failed to retrieve source content : %s", source);
			withMessage(message, Level.SEVERE);
			withState(State.FAILED);
			return List.of();
		}
	}

	@Override
	public synchronized void close() throws IOException {
		withMessage("Closing resources", Level.INFO);
		final String date = LocalDate
				.now()
				.toString();
		final String time = LocalTime
				.now()
				.toString();
		try {
			final SearchDocument document = new SearchDocument(
					source,
					date,
					time,
					List.copyOf(retrieved));
			handler.writeDocument(document);
		} catch (final IOException exception) {
			String message = String.format("Failed to write search document : %s", source);
			withMessage(message, Level.SEVERE);
			withState(State.FAILED);
			throw exception;
		}
		try {
			handler.close();
		} catch (final IOException exception) {
			String message = String.format("Closing resources safely failed : %s", source);
			withMessage(message, Level.SEVERE);
			withState(State.FAILED);
			throw exception;
		}
		withMessage("Close resources safely completed", Level.INFO);
	}

	@Override
	protected synchronized URL operate(final @NonNull DomNode operand) {
		Node hrefNode = operand.getAttributes().getNamedItem("href");
		String hrefAttribute = hrefNode != null ? hrefNode.getTextContent() : null;
		if (hrefAttribute == null) {
			withMessage(String.format("Could not read operand: %s", operand.getTextContent()), Level.WARNING);
			return null;
		}
		withMessage(String.format("Reading operand: %s", hrefAttribute), Level.INFO);
		try {
			URL url = pageContent.getFullyQualifiedUrl(hrefAttribute);
			return url;
		} catch (final MalformedURLException exception) {
			String message = String.format("Skipping retrieved malformed URL from source : %s", source);
			withMessage(message, Level.SEVERE);
			return null;
		}
	}

	@Override
	protected synchronized boolean result(URL operand) {
		if (operand != null) {
			withMessage(String.format("Queued result : %s", operand), Level.INFO);
			return retrieved.add(operand);
		}
		return false;
	}

	@Override
	protected synchronized void restart() throws IOException {
		withMessage("Restarting", Level.INFO);
		retrieved.clear();
		try {
			handler.close();
			handler = JsonHandler.acquireWriter(destination, _serializer);
		} catch (final IOException exception) {
			withMessage("Restart failed", Level.SEVERE);
			throw exception;
		}
		withMessage("Restart completed", Level.INFO);
	}
}
