package com.github.jelatinone.scholarfind.models;

import java.net.URL;
import java.time.LocalDate;
import java.util.Collection;

public record AnnotateDocument(
		String name,
		URL domain,
		Double award,
		LocalDate open,
		LocalDate close,
		Collection<String> supplements,
		Collection<String> requirements) {
}
