package com.github.jelatinone.task;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.w3c.dom.Node;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import com.github.jelatinone.Task;

public final class SearchTask implements Task<DomNode, Optional<String>> {
	private static final Map<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();
	private static final String BASE_DESTINATION = ("output/search-results_%s.json");

	private final String TARGET_URI;
	private final String DESTINATION_FILE;

	private final WebClient WEB_DRIVER;

	private SearchTask(final String Target, final String Destination) {
		TARGET_URI = Target;
		DESTINATION_FILE = Destination;

		WEB_DRIVER = new WebClient(BrowserVersion.BEST_SUPPORTED);
		WEB_DRIVER
				.getOptions()
				.setDownloadImages(false);
		WEB_DRIVER
				.getOptions()
				.setTimeout(3500);
		WEB_DRIVER
				.getOptions()
				.setCssEnabled(false);
		WEB_DRIVER
				.getOptions()
				.setJavaScriptEnabled(false);
		WEB_DRIVER
				.getOptions()
				.setThrowExceptionOnScriptError(false);
		WEB_DRIVER
				.getOptions()
				.setPrintContentOnFailingStatusCode(false);
	}

	@Override
	public Optional<String> node(final DomNode Work) {
		// Needs improvement; score-based solution..?
		Work.getAttributes().getNamedItem("href").

		return Optional.ofNullable(Work.getAttributes())
				.map(attributes -> attributes.getNamedItem("href"))
				.map(Node::getTextContent);
	}

	@Override
	public void run() {
		final String date = LocalDate
				.now()
				.toString();
		final JsonFactory factory = new JsonFactory();

		try (FileWriter writer = new FileWriter(new File(DESTINATION_FILE));
				JsonGenerator generator = factory.createJsonGenerator(writer)) {
			generator.useDefaultPrettyPrinter();

			Object lock = FILE_LOCKS.computeIfAbsent(DESTINATION_FILE, (destination) -> {
				System.err.printf("Creating new file lock: %s\n", destination);
				return new Object();
			});
			synchronized (lock) {
				System.err.printf("Started search at: %s\n", TARGET_URI);
				generator.writeStartObject();
				generator.writeStringField("href", TARGET_URI);
				generator.writeStringField("date", date);
				generator.writeFieldName("results");
				generator.writeStartArray();
				WEB_DRIVER
						.<HtmlPage>getPage(TARGET_URI)
						.querySelectorAll((".cb-link-blue"))
						.stream()
						.map(this::node)
						.forEach((optional) -> optional.ifPresent((href) -> {
							try {
								System.err.printf("Found compatible URI at: %s\n", href);
								generator.writeString(href);
							} catch (final IOException exception) {
								throw new RuntimeException(exception);
							}
						}));
				generator.writeEndArray();
				generator.writeEndObject();
				generator.flush();
				System.err.printf("Completed search at: %s\n", TARGET_URI);
			}
		} catch (final IOException exception) {
			exception.printStackTrace();
			System.err.printf("Failed search at: %s\n", TARGET_URI);
		} finally {
			System.err.println("Completed Search Task!");
			WEB_DRIVER.close();
		}
	}

	public static List<SearchTask> fromCommand(final CommandLine command, final String fromOption,
			final String toOption) {
		String[] fromValues = command.getOptionValues(fromOption);
		String toValue = Optional.ofNullable(command.getOptionValue(toOption))
				.orElse(String.format(BASE_DESTINATION, LocalDate.now().toString()));

		if (fromValues == null) {
			return List.of();
		}

		return Arrays.stream(fromValues)
				.map(from -> new SearchTask(from, toValue))
				.collect(Collectors.toList());
	}
}
