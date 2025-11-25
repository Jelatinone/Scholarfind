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

/**
 * <h1>JsonHandler</h1>
 * 
 * <p>
 * Handles JSON retrieval and writing for a given Document type which can be
 * serialized using a {@link #serializer}.
 * </p>
 * 
 * @author Cody Washington
 */
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class JsonHandler<Document> implements Closeable {

	static Logger _logger = Logger.getLogger(JsonHandler.class.getName());
	static Map<String, JsonHandler<?>> _handlers = new ConcurrentHashMap<>();

	JsonGenerator generator;
	JsonSerializer<Document> serializer;

	String destination;
	AtomicInteger references = new AtomicInteger();

	/**
	 * JSON Handler Constructor.
	 * 
	 * @param destination Target location to output {@link #writeDocument(Object)
	 *                    write operations} to
	 * @param serializer  {@link #serializer Serializer} to use to write to Target
	 *                    destination file
	 * @throws IOException When a critical IO failure occurs during construction
	 */
	private JsonHandler(final @NonNull String destination, final @NonNull JsonSerializer<Document> serializer)
			throws IOException {
		this.serializer = serializer;
		this.destination = destination;

		final JsonFactory factory = new JsonFactory();
		final File file = new File(destination);

		final ObjectMapper mapper = new ObjectMapper(factory);
		final ArrayNode document = acquireContent(file, mapper);

		final FileWriter writer = new FileWriter(file);
		generator = factory.createJsonGenerator(writer);

		synchronized (generator) {
			generator.writeStartObject();
			generator.writeFieldName("results");

			generator.writeStartArray();

			for (final JsonNode entry : document) {
				generator.writeTree(entry);
			}

			generator.flush();
		}
	}

	/**
	 * 
	 * Synchronously writes a Document object to a JSON file using the internal
	 * JsonGenerator and provided serializer, then flushes the content safely.
	 * 
	 * @param document Content to write to internal JSON file
	 * @throws IOException When a critical IO failure occurs while trying to write
	 *                     with the generator
	 */
	public synchronized void writeDocument(final @NonNull Document document) throws IOException {
		try {
			synchronized (generator) {
				serializer.write(generator, document);
				generator.flush();
			}
		} catch (final IOException exception) {
			String message = "Failed to write JSON document";
			_logger.severe(message);
			throw exception;
		}
	}

	@Override
	public synchronized void close() throws IOException {
		final int count = references.decrementAndGet();
		if (count > 0) {
			return;
		}
		try {
			synchronized (generator) {
				generator.writeEndArray();
				generator.writeEndObject();
				generator.flush();
				generator.close();
			}
		} catch (final IOException exception) {
			String message = "Failed to close JSON writer safely";
			_logger.severe(message);
			throw exception;
		} finally {
			_handlers.remove(destination);
		}
	}

	/**
	 * <p>
	 * Acquires a writer for a given file, which may or may not be shared by several
	 * threads.
	 * </p>
	 * 
	 * <p>
	 * If a writer already exists, a new instance is not created.
	 * </p>
	 * 
	 * @param <Document>  Document type expect to write and read with
	 * @param destination Target location to output {@link #writeDocument(Object)
	 *                    write operations} to
	 * @param serializer  {@link #serializer Serializer} to use to write to Target
	 *                    destination file
	 * @return An instance of {@link #JsonHandler(String, JsonSerializer)
	 *         JsonHandler} which may or may not be in use by another thread
	 * @throws IOException When a critical IO failure occurs during construction
	 */
	@SuppressWarnings("unchecked")
	public static <Document> JsonHandler<Document> acquireWriter(
			final @NonNull String destination,
			final @NonNull JsonSerializer<Document> serializer) throws IOException {
		final JsonHandler<Document> writer = (JsonHandler<Document>) _handlers.computeIfAbsent(destination, (target) -> {
			try {
				return new JsonHandler<>(destination, serializer);
			} catch (final IOException exception) {
				String message = "Failed to create JSON writer";
				_logger.severe(message);
				throw new RuntimeException(message, exception);
			}
		});
		writer.references.incrementAndGet();
		return writer;
	}

	/**
	 * Acquires an {@link ArrayNode} from a given file using the supplied Object
	 * mapper
	 * 
	 * @param file   JSON file to pull data from
	 * @param mapper JSON mapper to pull data with
	 * @return ArrayNode of content, which may be empty if no results could be
	 *         found, but never null
	 * @throws IOException When a critical IO failure occurs during read operation
	 */
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
