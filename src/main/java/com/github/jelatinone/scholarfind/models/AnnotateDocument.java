package com.github.jelatinone.scholarfind.models;

import java.net.URL;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

public record AnnotateDocument(
		String Name,
		URL Domain,
		Number Award,
		Optional<LocalDate> Open,
		Optional<LocalDate> Close,
		Map<String, String> Supplement,
		Map<String, String> Requirements) {
}
