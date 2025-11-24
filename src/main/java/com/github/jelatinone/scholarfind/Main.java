package com.github.jelatinone.scholarfind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
import com.github.jelatinone.scholarfind.tasks.SearchTask;

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
		Option opt_maxThreads = Option.builder()
				.longOpt("maxThreads")
				.option("with")
				.hasArg()
				.desc("number of threads to run a given operation with")
				.converter(Integer::valueOf)
				.valueSeparator('=')
				.get();
		_config.addOption(opt_maxThreads);
		Option opt_executorType = Option.builder()
				.longOpt("executorType")
				.option("using")
				.hasArg()
				.desc("Type of executor to run a given operation with")
				.converter((value) -> ExecutorType.valueOf(value.toUpperCase()))
				.get();
		_config.addOption(opt_executorType);
		Option opt_task = Option.builder()
				.longOpt("task")
				.option("t")
				.valueSeparator(',')
				.hasArgs()
				.desc("Defines a given task with arguments.")
				.get();
		_config.addOption(opt_task);
		try {
			_logger.fine(String.format("Main :: Supplied with command : %s", String.join(" ", arguments)));
			CommandLine command = _parser.parse(_config, arguments);
			_logger.fine(String.format("Main :: Parsed as command : %s", String.join(" ", command.getArgs())));
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

				case VIRTUAL:
					// Experimental Language Feature
					// _executor = Executors.newVirtualThreadPerTaskExecutor();
					// break;

				default:
					_executor = Executors.newCachedThreadPool();
					break;
			}

			String[] kindArguments = command.getOptionValues(opt_task);
			if (kindArguments == null) {
				_logger.severe("Main :: Could not parse argument(s) : no runnable tasks found!");
				return;
			}

			List<String> kindBlock = new ArrayList<>();
			for (final String kindArgument : kindArguments) {
				TaskKind maybeKind = null;
				try {
					maybeKind = TaskKind.valueOf(kindArgument.toUpperCase());
				} catch (final IllegalArgumentException ignored) {
				}

				if (maybeKind != null && !kindBlock.isEmpty()) {
					submit(kindBlock);
					kindBlock.clear();
				}
				kindBlock.add(kindArgument);
			}
			submit(kindBlock);
		} catch (final ParseException exception) {
			_logger.severe(String.format("Main :: Could not parse argument(s) : %s", exception.getMessage()));
			exception.printStackTrace();
		}
		try {
			update();
			_logger.fine(String.format("Main :: Executing [%s] tasks", _tasks.size()));
			CompletableFuture<?>[] tasks = _tasks.keySet()
					.stream()
					.map(Task::completable)
					.toArray(CompletableFuture[]::new);
			CompletableFuture.allOf(tasks).join();
		} finally {
			_executor.close();
		}
	}

	private static void update() {
		System.out.print("\033[2J\033[H");
		System.out.flush();
		synchronized (_tasks) {
			for (var task : _tasks.keySet()) {
				System.out.println(task.getReport());
			}
		}
	}

	private static void submit(List<String> kindArguments) {
		String[] kindBlock = kindArguments.toArray(String[]::new);

		TaskKind kind = TaskKind.valueOf(kindArguments.get(0).toUpperCase());

		Task<?, ?> task = kind.generator.apply(kindBlock);
		Future<?> future = _executor.submit(() -> {
			task.run();
			try {
				task.close();
			} catch (final Exception exception) {
				_logger.fine(String.format("Main :: Failed to close %s", task.getName()));
				exception.printStackTrace();
			}
		});
		task.withListener(Main::update);
		synchronized (_tasks) {
			_tasks.put(task, future);
		}
	}

	static enum TaskKind {
		SEARCH(SearchTask::new);

		final Function<String[], Task<?, ?>> generator;

		private TaskKind(final Function<String[], Task<?, ?>> generator) {
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
