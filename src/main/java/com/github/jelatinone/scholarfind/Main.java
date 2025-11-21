package com.github.jelatinone.scholarfind;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.github.jelatinone.scholarfind.meta.Task;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@FieldDefaults(level = AccessLevel.PRIVATE)
public final class Main {
	static final Logger _logger = Logger.getLogger(Main.class.getName());

	static final CommandLineParser _parser = new DefaultParser();

	static ExecutorService _executor;
	static Map<Task<?, ?>, Future<?>> _tasks = new ConcurrentHashMap<>();

	public static void main(String... arguments) {
		Options configuredOptions = new Options();

		Option opt_helpMessage = new Option("help", "print a descriptive help message");
		configuredOptions.addOption(opt_helpMessage);
		Option opt_maxThreads = Option.builder("maxThreads")
				.argName("with")
				.desc("number of threads to run a given operation with")
				.valueSeparator('=')
				.get();
		configuredOptions.addOption(opt_maxThreads);

		Option opt_executorType = Option.builder("executorType")
				.argName("as")
				.desc("Type of executor to run a given operation with")
				.converter(ExecutorType::valueOf)
				.get();
		configuredOptions.addOption(opt_executorType);
		Option opt_logToFile = Option.builder("logFile")
				.argName("file")
				.hasArg()
				.valueSeparator(' ')
				.desc("file to log resulting data to")
				.get();
		configuredOptions.addOption(opt_logToFile);

		Option taskMarker = Option.builder()
				.longOpt("task")
				.hasArg()
				.desc("Type of task <search, annotate, etc...>")
				.get();
		configuredOptions.addOption(taskMarker);

		try {
			_logger.fine(String.format("Supplied with command : %s", String.join(" ", arguments)));
			CommandLine command = _parser.parse(configuredOptions, arguments);
			_logger.fine(String.format("Parsed as command : %s", String.join(" ", command.getArgs())));
			Integer maxThreads = command.<Integer>getParsedOptionValue(opt_maxThreads);
			ExecutorType executorType = command.<ExecutorType>getParsedOptionValue(opt_executorType);
			switch (executorType) {
				case FIXED:
					_executor = Executors.newFixedThreadPool(maxThreads);
					break;

				case WORK_STEALING:
					_executor = Executors.newWorkStealingPool(maxThreads);
					break;

				case SCHEDULED:
					_executor = Executors.newScheduledThreadPool(maxThreads);
					break;

				default:
					_executor = Executors.newVirtualThreadPerTaskExecutor();
					break;
			}

			String[] kinds = command.getOptionValues(taskMarker);

			if (kinds == null) {
				_logger.severe("Could not parse argument(s) : no runnable tasks found!");
				return;
			}

			for (String kind : kinds) {
				final Task<?, ?> task = TaskType
						.valueOf(kind).generator
						.apply(command);
				final Future<?> future = _executor.submit(task);
				_tasks.put(task, future);
			}

		} catch (final ParseException exception) {
			String message = String.format("Could not parse argument(s) : %s", exception.getMessage());
			_logger.severe(message);
			exception.printStackTrace();
		}
		try {
			_logger.fine(String.format("Executing [%s] tasks", _tasks.size()));
			while (!_tasks.values().stream().allMatch(Future::isDone)) {
				System.out.flush();
				_tasks.forEach((Task, Future) -> {
					String message = String.format(
							"%s [%s] :: %s",
							Task.getName(),
							Task.getState(),
							Task.report());
					_logger.fine(message);
					System.out.println(message);
				});
			}
		} finally {
			_executor.close();
		}
	}

	static enum TaskType {
		;

		final String value;
		final Function<CommandLine, Task<?, ?>> generator;

		private TaskType(final @NonNull String value, final Function<CommandLine, Task<?, ?>> generator) {
			this.value = value;
			this.generator = generator;
		}
	}

	static enum ExecutorType {
		FIXED("fixed"),
		WORK_STEALING("stealing"),
		SCHEDULED("scheduled"),
		VIRTUAL("virtual");

		final String value;

		private ExecutorType(final @NonNull String value) {
			this.value = value;
		}
	}
}
