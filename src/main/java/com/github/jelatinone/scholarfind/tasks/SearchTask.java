package com.github.jelatinone.scholarfind.tasks;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.jelatinone.scholarfind.meta.State;
import com.github.jelatinone.scholarfind.meta.Task;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.w3c.dom.Node;

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SearchTask extends Task<DomNode, String> {
	static Options _config = new Options();
	static Logger _logger = Logger.getLogger(SearchTask.class.getName());

	static Map<String, JsonGenerator> _generators = new ConcurrentHashMap<>();
	static Map<String, AtomicInteger> _references = new ConcurrentHashMap<>();

	static String BASE_DESTINATION = "output/search-results_%s.json";
	static Integer BASE_TIMEOUT = 3500;

	@NonFinal
	JsonGenerator _writer;
	@NonFinal
	String _source;
	@NonFinal
	String _destination;
	@NonFinal
	Integer _timeout;

	static {
		Option opt_sourceTarget = Option.builder()
				.longOpt("from")
				.hasArg()
				.valueSeparator('|')
				.required()
				.desc("location to pull consumable source data from")
				.get();
		_config.addOption(opt_sourceTarget);
		Option opt_destinationTarget = Option.builder()
				.longOpt("to")
				.hasArg()
				.valueSeparator('|')
				.desc("location to push resulting produced data to")
				.get();
		_config.addOption(opt_destinationTarget);
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

	public SearchTask(final @NonNull CommandLine command) {
		super("SearchTask");
		message("Initializing");

		String sourceTarget = command.getOptionValue("from");
		_source = sourceTarget;

		String destinationTarget = command.getOptionValue("to");
		_destination = destinationTarget != null ? destinationTarget : BASE_DESTINATION;

		try {
			Integer networkTimeout = command.getParsedOptionValue("timeout");
			_timeout = networkTimeout != null ? networkTimeout : BASE_TIMEOUT;
		} catch (final ParseException exception) {
			_logger.severe(String.format("%s [%s] :: Failed to parse arguments", getName(), getState()));
			_timeout = BASE_TIMEOUT;
		}
		_writer = acquireGenerator(destinationTarget);
		message("Initialized");
	}

	private ArrayNode acquireContent(File file, ObjectMapper mapper) throws IOException {
		if (!file.exists() || file.length() == 0) {
			return mapper.createArrayNode();
		}
		try {
			JsonNode root = mapper.readTree(file);
			JsonNode results = root.get("results");

			if (results != null && results.isArray()) {
				return (ArrayNode) results;
			} else {
				return mapper.createArrayNode();
			}
		} catch (final Exception e) {
			throw new RuntimeException("Failed to parse existing JSON: " + file, e);
		}
	}

	private JsonGenerator acquireGenerator(final @NonNull String destination) {
		final JsonGenerator _generator = _generators.computeIfAbsent(destination, (target) -> {
			try {
				final JsonFactory factory = new JsonFactory();

				final File file = new File(target);

				final FileWriter writer = new FileWriter(file);
				final ObjectMapper mapper = new ObjectMapper();

				final JsonGenerator generator = factory.createJsonGenerator(writer);

				final ArrayNode content = acquireContent(file, mapper);

				generator.writeStartObject();
				generator.writeFieldName("results");
				generator.writeStartArray();
				for (final JsonNode entry : content) {
					generator.writeStartObject();

					final String sourceField = entry.get("source").textValue();
					generator.writeStringField("source", sourceField);

					final String dateField = entry.get("date").textValue();
					generator.writeStringField("date", dateField);

					final String timeField = entry.get("time").textValue();
					generator.writeStringField("time", timeField);

					generator.writeFieldName("retrieved");
					generator.writeStartArray();
					entry.get("retrieved").iterator().forEachRemaining((value) -> {
						try {
							generator.writeString(value.textValue());
						} catch (final IOException exception) {
							_logger.severe(String.format("%s [%s] :: Failed to parse existing content", getName(), getState()));
							throw new RuntimeException(exception);
						}
					});
					generator.writeEndArray();
					generator.writeEndObject();
				}

				return generator;
			} catch (final IOException exception) {
				_logger.severe(String.format("%s [%s] :: Failed to create JSON writer", getName(), getState()));
				throw new RuntimeException(exception);
			}
		});
		final String date = LocalDate
				.now()
				.toString();
		final String time = LocalTime
				.now()
				.toString();
		try {
			synchronized (_generator) {
				_generator.writeStartObject();
				_generator.writeStringField("source", _source);
				_generator.writeStringField("date", date);
				_generator.writeStringField("time", time);
				_generator.writeFieldName("retrieved");
				_generator.writeStartArray();
			}
		} catch (final IOException exception) {
			_logger.severe(String.format("%s [%s] :: Failed to create JSON writer", getName(), getState()));
			exception.printStackTrace();
		}
		return _generator;
	}

	private void releaseGenerator(final @NonNull String destination) {
		final AtomicInteger _reference = _references.get(destination);
		if (_reference == null) {
			return;
		}
		final Integer _count = _reference.decrementAndGet();
		if (_count > 0) {
			return;
		}
		try {
			JsonGenerator _generator = _generators.remove(destination);
			synchronized (_generator) {
				_generator.writeEndArray();
				_generator.writeEndObject();
				_generator.flush();
				_generator.close();
			}
		} catch (final IOException exception) {
			_logger.severe(String.format("%s [%s] :: Failed to close JSON writer", getName(), getState()));
			exception.printStackTrace();
		}
		_references.remove(destination);
	}

	protected synchronized List<DomNode> collect() {
		try (WebClient Client = new WebClient(BrowserVersion.BEST_SUPPORTED)) {
			message("Configuring Collection");
			Client
					.getOptions()
					.setDownloadImages(false);
			Client
					.getOptions()
					.setTimeout(3500);
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
			message("Reading page content");
			final HtmlPage pageContent = Client
					.<HtmlPage>getPage(_source);
			message("Retrieving page anchor tags");
			final List<DomNode> pageAnchors = pageContent.querySelectorAll(("a"))
					.stream()
					.filter(DomNode::hasAttributes)
					.filter(Objects::isNull)
					.toList();
			message(String.format("Found %s anchors", pageAnchors.size()));
			return pageAnchors;
		} catch (final IOException exception) {
			modify(State.FAILED);
			_logger.severe(String.format("%s [%s] :: Failed to retrieve source content", getName(), getState()));
			exception.printStackTrace();
			return List.of();
		}
	}

	@Override
	public void close() throws IOException {
		synchronized (_writer) {
			_writer.writeEndArray();
			_writer.writeEndObject();
		}
		releaseGenerator(_destination);
		message("Closed");
	}

	@Override
	protected String operate(final @NonNull DomNode operand) {
		Node hrefNode = operand.getAttributes().getNamedItem("href");
		String hrefAttribute = hrefNode != null ? hrefNode.getTextContent() : null;
		message(String.format("Reading operand: %s", hrefAttribute));
		return hrefAttribute;
	}

	@Override
	protected boolean result(@NonNull String operand) {
		synchronized (_writer) {
			try {
				message(String.format("Writing result: %s", operand));
				_writer.writeString(operand);
				return true;
			} catch (final IOException exception) {
				_logger.severe(String.format("%s [%s] :: Failed to write destination content", getName(), getState()));
				exception.printStackTrace();
				return false;
			}
		}
	}

	@Override
	protected synchronized void restart() {
		message("Restarting");
		releaseGenerator(_destination);
		_writer = acquireGenerator(_destination);
	}
}
