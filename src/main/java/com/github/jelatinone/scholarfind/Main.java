package com.github.jelatinone.scholarfind;

import java.util.ArrayList;
import java.util.List;
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
import lombok.experimental.FieldDefaults;

@FieldDefaults(level = AccessLevel.PRIVATE)
public final class Main {
	static final Logger _logger = Logger.getLogger(Main.class.getName());
	static final CommandLineParser _parser = new DefaultParser();
	static final Options _config = new Options();

	static ExecutorService _executor;
	static Map<Task<?, ?>, Future<?>> _tasks = new ConcurrentHashMap<>();

	public static void main(String... arguments) {
		Option opt_helpMessage = new Option("help", "print a descriptive help message");
		_config.addOption(opt_helpMessage);

		Option opt_maxThreads = Option.builder("maxThreads")
				.argName("with")
				.desc("number of threads to run a given operation with")
				.valueSeparator('=')
				.get();
		_config.addOption(opt_maxThreads);

		Option opt_executorType = Option.builder("executorType")
				.argName("as")
				.desc("Type of executor to run a given operation with")
				.converter((value) -> ExecutorType.valueOf(value.toUpperCase()))
				.get();
		_config.addOption(opt_executorType);

		Option opt_logToFile = Option.builder("logFile")
				.argName("file")
				.hasArg()
				.valueSeparator(' ')
				.desc("file to log resulting data to")
				.get();
		_config.addOption(opt_logToFile);

		Option opt_task = Option.builder()
				.longOpt("task")
				.hasArgs()
				.desc("Defines a given task with arguments.")
				.get();
		_config.addOption(opt_task);

		try {
			_logger.fine(String.format("Supplied with command : %s", String.join(" ", arguments)));
			CommandLine command = _parser.parse(_config, arguments);
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

			String[] kindArguments = command.getOptionValues(opt_task);
			if (kindArguments == null) {
				_logger.severe("Could not parse argument(s) : no runnable tasks found!");
				return;
			}

			List<String> kindBlock = new ArrayList<>();
			for (final String kindArgument : kindArguments) {
				TaskKind maybeKind;
				try {
					maybeKind = TaskKind.valueOf(kindArgument);
				} catch (final IllegalArgumentException exception) {
					maybeKind = null;
				}
				if (maybeKind != null && !kindBlock.isEmpty()) {
					submit(kindBlock);
				}
				kindBlock.add(kindArgument);
			}
			submit(kindBlock);
		} catch (final ParseException exception) {
			String message = String.format("Could not parse argument(s) : %s", exception.getMessage());
			_logger.severe(message);
			exception.printStackTrace();
		}
		try {
			long initialTime = System.currentTimeMillis() / 1000;
			_logger.fine(String.format("Executing [%s] tasks", _tasks.size()));
			while (!_tasks.values().stream().allMatch(Future::isDone)) {
				System.out.flush();
				synchronized (_tasks) {
					_tasks.forEach((Task, Future) -> {
						String message = String.format(
								"%s [%s] :: %s",
								Task.getName(),
								Math.floor(System.currentTimeMillis() / 1000 - initialTime),
								Task.report());
						_logger.fine(message);
						System.out.println(message);
					});
				}
			}
		} finally {
			_executor.close();
		}
	}

	private static void submit(List<String> kindArguments) {
		String[] kindBlock = kindArguments.toArray(String[]::new);
		try {
			CommandLine kindCommand = _parser.parse(_config, kindBlock);
			TaskKind kind = TaskKind.valueOf(kindArguments.get(0).toUpperCase());

			Task<?, ?> task = kind.generator.apply(kindCommand);
			Future<?> future = _executor.submit(task);
			synchronized (_tasks) {
				_tasks.put(task, future);
			}
		} catch (final ParseException exception) {
			String message = String.format("Could not parse argument(s) : %s", exception.getMessage());
			_logger.severe(message);
			System.out.println(message);
			exception.printStackTrace();
		}
	}

	static enum TaskKind {
		;

		final Function<CommandLine, Task<?, ?>> generator;

		private TaskKind(final Function<CommandLine, Task<?, ?>> generator) {
			this.generator = generator;
		}
	}

	static enum ExecutorType {
		FIXED,
		WORK_STEALING,
		SCHEDULED,
		VIRTUAL;
	}
}
