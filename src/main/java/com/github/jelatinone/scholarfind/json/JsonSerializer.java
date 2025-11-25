package com.github.jelatinone.scholarfind.json;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;

@FunctionalInterface
public interface JsonSerializer<T> {

	void write(JsonGenerator generator, T document) throws IOException;
}
