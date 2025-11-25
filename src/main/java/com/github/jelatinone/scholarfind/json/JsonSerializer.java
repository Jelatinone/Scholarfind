package com.github.jelatinone.scholarfind.json;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;

import lombok.NonNull;

@FunctionalInterface
public interface JsonSerializer<Document> {

	void write(final @NonNull JsonGenerator generator, final @NonNull Document document) throws IOException;
}
