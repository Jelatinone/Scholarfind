package com.github.jelatinone.task;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.github.jelatinone.Task;

public class AnnotationTask implements Task<HtmlPage, Boolean> {
	private static final Map<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();
	private static final String BASE_DESTINATION = ("output/annotation-results_%s.json");

	private final String TARGET_FILE;
	private final String DESTINATION_FILE;

	private final WebDriver WEB_DRIVER;
	private final Scanner INPUT_SCANNER;

	private AnnotationTask(final String Target, final String Destination) {
		TARGET_FILE = Target;
		DESTINATION_FILE = Destination;

		WEB_DRIVER = new FirefoxDriver();
		INPUT_SCANNER = new Scanner(System.in);
	}

	@Override
	public Boolean node(final HtmlPage pageContent) {
		// More abstract solution for finding dates...?
		final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("M/d/yyyy");
		final LocalDate openDate = LocalDate.parse(pageContent
				.querySelector(".sc-d233e5e8-1 > div:nth-child(1) > span:nth-child(2)")
				.getTextContent().trim(),
				formatter);
		final LocalDate closeDate = LocalDate.parse(pageContent
				.querySelector(".sc-d233e5e8-1 > div:nth-child(2) > span:nth-child(2)")
				.getTextContent().trim(),
				formatter);
		final LocalDate currentDate = LocalDate.now();
		if (currentDate.isBefore(openDate)) {
			return false;
		}
		if (currentDate.isAfter(closeDate)) {
			return false;
		}
		WEB_DRIVER.get(pageContent.getBaseURI());
		System.out.print("Approve? (Y/N): ");
		System.out.flush();
		while (!INPUT_SCANNER.hasNextLine()) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException ignored) {
			}
		}

		String line = INPUT_SCANNER.nextLine().trim();
		if (line.isEmpty())
			return false;

		return line.charAt(0) == 'y';
	}

	@Override
	public void run() {
		final String date = LocalDate
				.now()
				.toString();
		final String time = LocalTime
				.now()
				.toString();
		final JsonFactory factory = new JsonFactory();

		try (FileReader reader = new FileReader(new File(TARGET_FILE));
				JsonGenerator generator = factory.createJsonGenerator(new FileWriter(new File(DESTINATION_FILE)));
				WebClient Client = new WebClient(
						BrowserVersion.BEST_SUPPORTED)) {
			Client
					.getOptions()
					.setDownloadImages(false);
			Client
					.getOptions()
					.setTimeout(3500);
			Client
					.getOptions()
					.setCssEnabled(false);
			Client
					.getOptions()
					.setJavaScriptEnabled(false);
			Client
					.getOptions()
					.setThrowExceptionOnScriptError(false);
			Client
					.getOptions()
					.setPrintContentOnFailingStatusCode(false);

			generator.useDefaultPrettyPrinter();
			Object lock = FILE_LOCKS.computeIfAbsent(DESTINATION_FILE, destination -> new Object());
			ObjectMapper mapper = new ObjectMapper(factory);
			synchronized (lock) {
				generator.writeStartObject();
				generator.writeStringField("date", date);
				generator.writeStringField("time", time);
				generator.writeFieldName("results");
				generator.writeStartArray();
				mapper
						.readTree(reader)
						.get("results")
						.iterator()
						.forEachRemaining((value) -> {
							String link = value.asText();
							System.err.printf("Found compatible URI at: %s\n", link);
							try {
								final HtmlPage pageContent = Client
										.<HtmlPage>getPage(link);
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
			System.err.printf("Failed annotation at: %s\n %s", TARGET_FILE, exception.getMessage());
		} finally {
			System.err.println("Completed Annotation Task!");
			WEB_DRIVER.close();
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
