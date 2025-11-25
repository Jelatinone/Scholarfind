package com.github.jelatinone.scholarfind.json;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;

@FunctionalInterface
public interface JsonSerializer<Document> {

	void write(JsonGenerator generator, Document document) throws IOException;
}
