package com.github.jelatinone.scholarfind.models;

import java.net.URL;
import java.util.List;

public record SearchDocument(
		String source,
		String date,
		String time,
		List<URL> retrieved) {
}
