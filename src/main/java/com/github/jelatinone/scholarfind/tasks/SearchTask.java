package com.github.jelatinone.scholarfind.tasks;

import com.github.jelatinone.scholarfind.json.JsonHandler;
import com.github.jelatinone.scholarfind.meta.State;
import com.github.jelatinone.scholarfind.meta.Task;
import com.github.jelatinone.scholarfind.models.SearchDocument;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
public final class SearchTask extends Task<DomNode, String> {
	static String DEFAULT_DESTINATION = "output/search-results_%s.json";
	static Integer DEFAULT_TIMEOUT = 3500;

	static Options _config = new Options();
	static Logger _logger = Logger.getLogger(SearchTask.class.getName());
	static CommandLineParser _parser = new DefaultParser();

	List<String> retrieved = new ArrayList<>();

	@NonFinal
	JsonHandler<SearchDocument> handler;
	@NonFinal
	String source;
	@NonFinal
	String destination;
	@NonFinal
	Integer timeout;

	static {
		_config.addOptions(Task.BASE_OPTION_CONFIGURATION);
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
		super("SearchTask");
		setMessage("Initializing");

		try {
			CommandLine command = _parser.parse(_config, arguments);
			String sourceTarget = command.getOptionValue("from");
			source = sourceTarget;

			String destinationTarget = command.getOptionValue("to");
			destination = destinationTarget != null ? destinationTarget
					: String.format(DEFAULT_DESTINATION, LocalDate
							.now()
							.toString());

			Integer networkTimeout = command.getParsedOptionValue("timeout");
			timeout = networkTimeout != null ? networkTimeout : DEFAULT_TIMEOUT;

			handler = JsonHandler.acquireWriter(destination, (generator, document) -> {
				generator.writeStartObject();
				generator.writeStringField("source", document.source());
				generator.writeStringField("date", document.date());
				generator.writeStringField("time", document.time());

				generator.writeFieldName("retrieved");
				generator.writeStartArray();
				for (String value : document.retrieved()) {
					generator.writeString(value);
				}
				generator.writeEndArray();

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

	protected synchronized List<DomNode> collect() {
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
					.<HtmlPage>getPage(source);
			setMessage("Retrieving page anchor tags");
			final List<DomNode> pageAnchors = pageContent.querySelectorAll(("a"))
					.stream()
					.filter(DomNode::hasAttributes)
					.filter(Objects::nonNull)
					.toList();
			setMessage(String.format("Found %s anchors", pageAnchors.size()));
			return pageAnchors;
		} catch (final IOException exception) {
			setState(State.FAILED);
			String message = String.format("%s [%s] :: Failed to retrieve source content", getName(), getState());
			_logger.severe(message);
			return List.of();
		}
	}

	@Override
	public synchronized void close() throws IOException {
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
			setState(State.FAILED);
			String message = String.format("%s [%s] :: Failed to write search document",
					getName(), getState());
			_logger.severe(message);
			throw exception;
		} finally {
			if (handler != null) {
				handler.close();
			}
			setMessage("Resource closed");
		}
	}

	@Override
	protected synchronized String operate(final @NonNull DomNode operand) {
		Node hrefNode = operand.getAttributes().getNamedItem("href");
		String hrefAttribute = hrefNode != null ? hrefNode.getTextContent() : null;
		setMessage(String.format("Reading operand: %s", hrefAttribute));
		return hrefAttribute;
	}

	@Override
	protected synchronized boolean result(@NonNull String operand) {
		setMessage(String.format("Queued result: %s", operand));
		return retrieved.add(operand);
	}

	@Override
	protected synchronized void restart() {
		setMessage("Restarting");
		retrieved.clear();
	}
}
