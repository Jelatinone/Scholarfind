package com.github.jelatinone.scholarfind.json;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class JsonHandler<Document> implements Closeable {

	static Logger _logger = Logger.getLogger(JsonHandler.class.getName());
	static Map<String, JsonHandler<?>> _handlers = new ConcurrentHashMap<>();

	JsonGenerator _generator;
	JsonSerializer<Document> _serializer;

	String _destination;
	AtomicInteger _references = new AtomicInteger();

	private JsonHandler(final @NonNull String destination, final @NonNull JsonSerializer<Document> serializer)
			throws IOException {
		final JsonFactory factory = new JsonFactory();
		final File file = new File(destination);

		final ObjectMapper mapper = new ObjectMapper(factory);

		final ArrayNode document = acquireContent(file, mapper);

		final FileWriter writer = new FileWriter(file);

		_generator = factory.createJsonGenerator(writer);
		_serializer = serializer;

		_destination = file.getPath();
		synchronized (_generator) {
			_generator.writeStartObject();
			_generator.writeFieldName("results");

			_generator.writeStartArray();

			for (final JsonNode entry : document) {
				_generator.writeTree(entry);
			}

			_generator.flush();
		}
	}

	public synchronized void writeDocument(final @NonNull Document document) throws IOException {
		try {
			synchronized (_generator) {
				_serializer.write(_generator, document);
				_generator.flush();
			}
		} catch (final IOException exception) {
			String message = "Failed to write JSON document";
			_logger.severe(message);
			throw exception;
		}
	}

	@Override
	public synchronized void close() throws IOException {
		final int count = _references.decrementAndGet();
		if (count > 0) {
			return;
		}
		try {
			synchronized (_generator) {
				_generator.writeEndArray();
				_generator.writeEndObject();
				_generator.flush();
				_generator.close();
			}
		} catch (final IOException exception) {
			String message = "Failed to close JSON writer safely";
			_logger.severe(message);
			throw exception;
		} finally {
			_handlers.remove(_destination);
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> JsonHandler<T> acquireWriter(
			final @NonNull String destination,
			final @NonNull JsonSerializer<T> serializer) throws IOException {
		final JsonHandler<T> writer = (JsonHandler<T>) _handlers.computeIfAbsent(destination, (target) -> {
			try {
				return new JsonHandler<>(destination, serializer);
			} catch (final IOException exception) {
				String message = "Failed to create JSON writer";
				_logger.severe(message);
				throw new RuntimeException(message, exception);
			}
		});
		writer._references.incrementAndGet();
		return writer;
	}

	public static ArrayNode acquireContent(
			final @NonNull File file,
			final @NonNull ObjectMapper mapper) throws IOException {

		if (!file.exists() || file.length() == 0) {
			return mapper.createArrayNode();
		}

		JsonNode root = mapper.readTree(file);
		JsonNode results = root.get("results");

		if (results != null && results.isArray()) {
			return (ArrayNode) results;
		} else {
			return mapper.createArrayNode();
		}
	}

}
