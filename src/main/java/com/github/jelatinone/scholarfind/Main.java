package com.github.jelatinone.scholarfind;

import java.io.IOException;
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
import com.github.jelatinone.scholarfind.tasks.AnnotateTask;
import com.github.jelatinone.scholarfind.tasks.SearchTask;

import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public final class Main {
	static Logger _logger = Logger.getLogger(Main.class.getName());

	static CommandLineParser _parser = new DefaultParser();
	static Options _config = new Options();

	static Map<Task<?, ?>, Future<?>> _tasks = new ConcurrentHashMap<>();
	static ExecutorService _executor;

	static {
		Option opt_helpMessage = new Option("help", "output a descriptive help message");
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
				.option("use")
				.hasArg()
				.desc("Type of executor to run a given operation with")
				.converter((value) -> ExecutorType.valueOf(value.toUpperCase()))
				.get();
		_config.addOption(opt_executorType);
		Option opt_task = Option.builder()
				.longOpt("task")
				.hasArgs()
				.desc("Defines a given task with arguments.")
				.get();
		_config.addOption(opt_task);
	}

	public static void main(final String... arguments) {
		try {
			CommandLine parsedCommand = _parser.parse(_config, arguments);

			for (String argument : arguments) {
				_logger.fine(String.format("Main :: supplied with argument :", argument));
			}
			for (String parsedArgument : parsedCommand.getArgList()) {
				_logger.fine(String.format("Main :: parsed with argument :", parsedArgument));
			}

			Integer maxThreads = parsedCommand.getParsedOptionValue("maxThreads");
			ExecutorType executorType = parsedCommand.getParsedOptionValue("executorType");

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
					// _executor = Executors.newVirtualThreadPerTaskExecutor();
					// break;

				default:
					_executor = Executors.newCachedThreadPool();
					break;
			}

			String[] taskArguments = parsedCommand.getParsedOptionValues("task");

			if (taskArguments == null) {
				_logger.severe("Main :: Could not parse argument(s) : no runnable tasks found!");
				return;
			}

			List<String> taskArgument = new ArrayList<>();

			for (String argument : taskArguments) {
				boolean argumentIsTask;
				try {
					TaskType.valueOf(argument.toUpperCase());
					argumentIsTask = true;
				} catch (final IllegalArgumentException ignored) {
					argumentIsTask = false;
				}

				if (argumentIsTask && !taskArgument.isEmpty()) {
					withTask(taskArgument);
					taskArgument.clear();
				}
				taskArgument.add(argument);
			}
			withTask(taskArgument);
		} catch (final ParseException exception) {
			_logger.severe(String.format("Main :: Could not parse argument(s) : %s",
					exception.getMessage()));
			return;
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

	/**
	 * Updates the live preview of Task behavior.
	 */
	private static void update() {
		System.out.print("\033[2J\033[H");
		System.out.flush();
		for (Task<?, ?> task : _tasks.keySet()) {
			System.out.println(task.getReport());
		}
	}

	/**
	 * 
	 * Adds a task to the Task graph, and immediately submits it for execution. Also
	 * adds a listener to this task to update the live {@link #update() update}.
	 * 
	 * @implNote Tasks submitted are not guaranteed to be run at the same time or
	 *           interval.
	 * 
	 * @param taskArgument List of arguments to pass to the task for construction
	 */
	private static void withTask(final List<String> taskArgument) {
		TaskType type = TaskType.valueOf(taskArgument.get(0).toUpperCase());
		Task<?, ?> task = type.create(taskArgument.toArray(String[]::new));
		Future<?> future = _executor.submit(() -> {
			try (task) {
				task.run();
			} catch (final IOException exception) {
				_logger.fine(String.format("Main :: %s failed to close %s", task.getName(), exception.getMessage()));
			} catch (final Exception exception) {
				_logger.fine(String.format("Main :: %s failed to run %s", task.getName(), exception.getMessage()));
			} catch (final Error error) {
				_logger.fine(String.format("Main :: %s critical error occurred %s", task.getName(), error.getMessage()));
			}
		});
		task.withListener(Main::update);
		_tasks.put(task, future);
	}

	static enum ExecutorType {
		FIXED,

		WORK_STEALING,

		SCHEDULED,

		VIRTUAL;
	}

	static enum TaskType {
		SEARCH(SearchTask::new),
		ANNOTATE(AnnotateTask::new);

		private final Function<String[], Task<?, ?>> factory;

		private TaskType(final Function<String[], Task<?, ?>> generator) {
			this.factory = generator;
		}

		public Task<?, ?> create(final String[] taskArgument) {
			return factory.apply(taskArgument);
		}
	}
}
