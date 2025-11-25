package com.github.jelatinone.scholarfind.json;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;

import lombok.NonNull;

/**
 * 
 * <h1>JsonSerializer</h1>
 * 
 * 
 * <p>
 * {@link #write(JsonGenerator, Object) writes} a given document to a JSON file
 * using a supplied generator, which may throw an {@link #IOException}.
 * </p>
 * 
 * 
 * @author Cody Washington
 */
@FunctionalInterface
public interface JsonSerializer<Document> {

	/**
	 * Writes the supplied document to the JSON file using the supplied generator
	 * 
	 * @param generator Generator to use to write to internal JSON file
	 * @param document  Content to write to internal JSON file
	 * @throws IOException When a critical IO failure has occurred that prevented
	 *                     the generator from flushing content
	 */
	void write(final @NonNull JsonGenerator generator, final @NonNull Document document) throws IOException;
}
