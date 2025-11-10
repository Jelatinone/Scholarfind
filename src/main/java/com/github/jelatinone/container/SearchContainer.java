package com.github.jelatinone.container;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.github.jelatinone.Container;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;


public class SearchContainer implements Container<DomNode, Optional<String>> {

  static final String TARGET = ("https://bigfuture.collegeboard.org/scholarships");
  static final String OUTPUT = ("output/search-results_%s_%s.json");

  final WebClient WEB_CLIENT;

  static volatile SearchContainer Instance;

  private SearchContainer() {
    WEB_CLIENT = new WebClient(BrowserVersion.FIREFOX);
    WEB_CLIENT.getOptions().setDownloadImages((false));
    WEB_CLIENT.getOptions().setTimeout(3500);
    WEB_CLIENT.getOptions().setCssEnabled((false));
    WEB_CLIENT.getOptions().setJavaScriptEnabled((false));
    WEB_CLIENT.getOptions().setThrowExceptionOnScriptError(false);
    WEB_CLIENT.getOptions().setPrintContentOnFailingStatusCode(false);
  }

  @Override
  public Optional<String> compute(final DomNode Node) {
    return Optional.ofNullable(Node.getAttributes())
      .map(attrs -> attrs.getNamedItem("href"))
      .map(org.w3c.dom.Node::getTextContent);
  }

  @Override
  public void run() {
    String Date = LocalDate.now().toString();

    JsonFactory Factory = new JsonFactory();

    try (FileWriter Writer = new FileWriter(String.format(OUTPUT, Date, "bigfuture")); JsonGenerator Generator = Factory.createJsonGenerator(Writer)) {
      Generator.useDefaultPrettyPrinter();
      Generator.writeStartObject();
      Generator.writeStringField("href", TARGET);
      Generator.writeStringField("date", Date);
      Generator.writeFieldName("links");
      Generator.writeStartArray();
      WEB_CLIENT
        .<HtmlPage>getPage(TARGET)
        .querySelectorAll((".cb-link-blue"))
        .stream()
        .map(this::compute)
        .forEach((optional) -> optional.ifPresent((href) -> {
          try {
            Generator.writeString(href);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }));

      Generator.writeEndArray();
      Generator.writeEndObject();
      Generator.flush();

    } catch (final IOException e) {
      e.printStackTrace();
    }
  }


  public static synchronized SearchContainer getInstance()
    throws UnsupportedOperationException {
    SearchContainer Result = Instance;
    if (Instance == (null)) {
      synchronized (Container.class) {
        Result = Instance;
        if (Instance == (null)) {
          Instance = Result = new SearchContainer();
        }
      }
    }
    return Result;
  }
}
