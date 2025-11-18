package com.github.jelatinone;

import java.util.Collection;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.github.jelatinone.task.AnnotationTask;
import com.github.jelatinone.task.SearchTask;

public class Main {
  public static void main(String[] args) {
    Options options = new Options();

    // General
    Option help = new Option("help", "print a descriptive usage message");
    options.addOption(help);

    Option maxThreads = Option.builder("maxThreads")
        .argName("with")
        .desc("number of threads to run a given operation with")
        .valueSeparator('=')
        .get();
    options.addOption(maxThreads);
    Option logFile = Option.builder("logFile")
        .argName("file")
        .hasArg()
        .valueSeparator(' ')
        .desc("file to log resulting data to")
        .get();
    options.addOption(logFile);
    Option maximumTimeout = Option.builder("maximumTimeout")
        .argName("timeout")
        .hasArg()
        .valueSeparator('=')
        .desc("maximum amount of time to wait for a response before aborting a network operation")
        .get();
    options.addOption(maximumTimeout);

    // Search
    Option searchFrom = Option.builder("searchFrom")
        .argName("searchFrom")
        .hasArg()
        .valueSeparator(' ')
        .desc("source coordinate to perform a search at")
        .get();
    searchFrom.setArgs(Option.UNLIMITED_VALUES);
    options.addOption(searchFrom);
    Option searchTo = Option.builder("searchTo")
        .argName("searchTo")
        .valueSeparator(' ')
        .hasArg()
        .desc("resulting destination for a given search result to arrive")
        .get();
    options.addOption(searchTo);

    // Annotation
    Option annotateFrom = Option.builder("annotateFrom")
        .argName("annotateFrom")
        .hasArg()
        .valueSeparator(' ')
        .desc("source coordinate to perform a annotation at")
        .get();
    searchFrom.setArgs(Option.UNLIMITED_VALUES);
    options.addOption(annotateFrom);
    Option annotateTo = Option.builder("annotateTo")
        .argName("annotateTo")
        .valueSeparator(' ')
        .hasArg()
        .desc("resulting destination for a given annotation result to arrive")
        .get();
    options.addOption(annotateTo);

    CommandLineParser parser = new DefaultParser();
    try {
      System.out.flush();
      CommandLine command = parser.parse(options, args);
      if (command.hasOption("searchFrom")) {
        System.err.println("Running Search Task...");
        Collection<SearchTask> searchTasks = SearchTask.fromCommand(
            command,
            "searchFrom",
            "searchTo");
        searchTasks.forEach(Task::run);
      }

      if (command.hasOption("annotateFrom")) {
        System.err.println("Running Annotation Task...");
        Collection<AnnotationTask> annotationTasks = AnnotationTask.fromCommand(
            command,
            "annotateFrom",
            "annotateTo");
        annotationTasks.forEach(Task::run);
      }

    } catch (final ParseException exception) {
      System.err.format("Failed to parse: \n%s", exception.getMessage());
    }
  }
}