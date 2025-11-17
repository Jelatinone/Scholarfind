package com.github.jelatinone.task;

import java.io.File;
import java.io.FileReader;
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
import org.htmlunit.html.HtmlPage;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jelatinone.Task;

public class AnnotationTask implements Task<HtmlPage, Boolean> {
	private static final Map<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();
	private static final String BASE_DESTINATION = ("output/annotation-results_%s.json");

	private final WebClient WEB_CLIENT;
	private final WebDriver WEB_DRIVER;

	private final String TARGET_FILE;
	private final String DESTINATION_FILE;

	private AnnotationTask(final String Target, final String Destination) {
		TARGET_FILE = Target;
		DESTINATION_FILE = Destination;

		WEB_CLIENT = new WebClient(BrowserVersion.BEST_SUPPORTED);
		WEB_CLIENT
				.getOptions()
				.setDownloadImages(false);
		WEB_CLIENT
				.getOptions()
				.setTimeout(3500);
		WEB_CLIENT
				.getOptions()
				.setCssEnabled(false);
		WEB_CLIENT
				.getOptions()
				.setJavaScriptEnabled(false);
		WEB_CLIENT
				.getOptions()
				.setThrowExceptionOnScriptError(false);
		WEB_CLIENT
				.getOptions()
				.setPrintContentOnFailingStatusCode(false);
		WEB_DRIVER = new ChromeDriver();
	}

	@Override
	public Boolean node(final HtmlPage pageContent) {
		// More abstract solution for finding dates...?
		final LocalDate openDate = LocalDate.parse(pageContent
				.querySelector(".sc-d233e5e8-1 > div:nth-child(1) > span:nth-child(2)")
				.getTextContent().trim());
		final LocalDate closeDate = LocalDate.parse(pageContent
				.querySelector(".sc-d233e5e8-1 > div:nth-child(2) > span:nth-child(2)")
				.getTextContent().trim());
		final LocalDate currentDate = LocalDate.now();

		if (!(currentDate.isAfter(openDate) || currentDate.isEqual(openDate))) {
			return false;
		}
		if (!(currentDate.isAfter(closeDate) || currentDate.isEqual(closeDate))) {
			return false;
		}

		WEB_DRIVER.get(pageContent.getBaseURI());
		try {
			Terminal system = TerminalBuilder
					.builder()
					.system(true)
					.build();
			System.out.println("Review: " + pageContent.getTitleText());
			System.out.print("[y/n]: ");
			int input = system.reader().read();
			return input == 'y' || input == 'Y';
		} catch (final IOException exception) {
			System.err.format("Failed to validate annotation: \n%s", exception.getMessage());
			return false;
		}
	}

	@Override
	public void run() {
		final String date = LocalDate
				.now()
				.toString();
		final JsonFactory factory = new JsonFactory();

		try (FileReader reader = new FileReader(new File(TARGET_FILE));
				JsonGenerator generator = factory.createJsonGenerator(new FileWriter(new File(DESTINATION_FILE)))) {
			generator.useDefaultPrettyPrinter();
			Object lock = FILE_LOCKS.computeIfAbsent(DESTINATION_FILE, destination -> new Object());
			ObjectMapper mapper = new ObjectMapper(factory);
			synchronized (lock) {
				generator.writeStartObject();
				generator.writeStringField("date", date);
				generator.writeFieldName("results");
				generator.writeStartArray();
				mapper
						.readTree(reader)
						.get("results")
						.iterator()
						.forEachRemaining((value) -> {
							String link = value.asText();
							try {
								final HtmlPage pageContent = WEB_CLIENT.<HtmlPage>getPage(link);
								if (node(pageContent)) {
									generator.writeString(link);
									generator.writeStartObject();
									String title = pageContent
											.querySelector(".sc-c64e2d48-3")
											.getTextContent();
									generator.writeStringField("title", title);
									String website = pageContent
											.querySelector("#about-scholarship-url-id-details-list-item-label")
											.getAttributes()
											.getNamedItem("href")
											.getTextContent();
									generator.writeStringField("website", website);
									String award = pageContent
											.querySelector(".sc-d233e5e8-0")
											.getTextContent()
											.trim();
									generator.writeStringField("award", award);
									generator.writeEndObject();
								}
							} catch (final IOException exception) {
								System.err.printf("Failed to parse at [%s]: \n%s", link, exception.getMessage());
							}
						});
				generator.writeEndArray();
				generator.writeEndObject();
				generator.flush();
				System.err.printf("Completed annotation at: %s\n", TARGET_FILE);
			}
		} catch (final IOException exception) {
			exception.printStackTrace();
			System.err.printf("Failed annotation at: %s\n", TARGET_FILE);
		} finally {
			System.err.println("Completed Annotation Task!");
			WEB_DRIVER.close();
			WEB_CLIENT.close();
		}
	}

	public static List<AnnotationTask> fromCommand(final CommandLine command, final String fromOption,
			final String toOption) {
		String[] fromValues = command.getOptionValues(fromOption);
		String toValue = Optional.ofNullable(command.getOptionValue(toOption))
				.orElse(String.format(BASE_DESTINATION, LocalDate.now().toString()));

		if (fromValues == null)
			return List.of();

		return Arrays.stream(fromValues)
				.map(from -> new AnnotationTask(from, toValue))
				.collect(Collectors.toList());
	}
}
