package com.github.jelatinone.scholarfind.meta;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

/**
 * 
 * <h1>Task</h1>
 * 
 * <p>
 * A generic description of a Tak which operates on the smallest possible unit
 * of `consumes` and outputs a result `produces`.
 * 
 * </p>
 * 
 * <p>
 * Used to perform mass operations of similar type `consumes` on a collection of
 * consumable data.
 * For example, a task which scrapes all of the data from a website, then parses
 * each individual tag and converts it to a `String`.
 * </p>
 * 
 * @author Cody Washington
 */
@FieldDefaults(level = AccessLevel.PROTECTED, makeFinal = true)
public abstract class Task<Consumes, Produces>
		implements Runnable, AutoCloseable {
	public static Integer MAXIMUM_OPERAND_RETRIES = 3;

	public static Options DEFAULT_OPTION_CONFIGURATION = new Options();
	public static String DEFAULT_DESTINATION_LOCATION = "output/%s-results_%s.json";
	public static Integer DEFAULT_NETWORK_TIMEOUT = 3500;

	static Logger _logger = Logger.getLogger(Task.class.getName());

	String _name;

	Collection<Task<?, ?>> _dependencies;
	Collection<Task<?, ?>> _dependents;
	Collection<Runnable> _listeners;

	AtomicReference<State> _state;
	CompletableFuture<Void> _completable;
	AtomicInteger _attempt;

	@NonFinal
	Consumes operand = null;

	@NonFinal
	Produces result = null;

	@NonFinal
	String message = null;

	static {
		Option opt_helpMessage = new Option("help", "print a descriptive help message");
		DEFAULT_OPTION_CONFIGURATION.addOption(opt_helpMessage);
		Option opt_sourceTarget = Option.builder()
				.longOpt("from")
				.required()
				.hasArg()
				.desc("location to pull consumable source data from")
				.get();
		DEFAULT_OPTION_CONFIGURATION.addOption(opt_sourceTarget);
		Option opt_destinationTarget = Option.builder()
				.longOpt("to")
				.hasArg()
				.desc("location to push resulting produced data to")
				.get();
		DEFAULT_OPTION_CONFIGURATION.addOption(opt_destinationTarget);
	}

	/**
	 * Creates a new abstract Task
	 * 
	 * @param name Name of the task to be created
	 */
	public Task(final @NonNull String name) {
		_name = name;

		_attempt = new AtomicInteger();
		_state = new AtomicReference<State>(State.CREATED);
		_completable = new CompletableFuture<>();

		_dependencies = new ArrayList<>();
		_dependents = new ArrayList<>();
		_listeners = new ArrayList<>();

		_logger.fine(String.format("%s [%s] :: Task Created!", getName(), getState()));
	}

	/**
	 * Collects all consumable data into a single collection for
	 * {@link #operate(Serializable) operation} to be performed on each element
	 * within the collection.
	 * 
	 * @return Collection of consumable data
	 */
	protected abstract List<@NonNull Consumes> collect();

	/**
	 * Performs an operation on `consumable` data and maps to a `producible` a
	 * result.
	 * 
	 * @param operand Data to be mapped
	 * @return Mapped result
	 */
	protected abstract Produces operate(final @NonNull Consumes operand);

	/**
	 * Self-callback function to determine the validity of the resulting data
	 * 
	 * @param operand Data to be checked
	 * @return Mapped result
	 */
	protected abstract boolean result(final Produces operand);

	/**
	 * Restarts the current `Task`, performs necessary clean-up operations on this
	 * instance before restarting.
	 * 
	 * @throws IOException When a critical failure has occurred while trying to
	 *                     restart
	 * 
	 * @apiNote Called only during {@link #run() operation} of this Task when a
	 *          {@link #setState(State) state modification} has occurred
	 * 
	 */
	protected abstract void restart() throws IOException;

	/**
	 * Provides the {@link CompletableFuture Future} of this `Task`
	 * 
	 * @apiNote This should be used to await the dependencies of the given task
	 *          using {@link CompletableFuture#join() join()}.
	 * 
	 * @return Completable future of the current operation
	 */
	public CompletableFuture<Void> completable() {
		return _completable;
	}

	/**
	 * Adds a dependent `Task` to this `Task`, which must be completed
	 * <em>before<em> running any {@link #operate(Serializable) operations} on the
	 * {@link #collect() data} of this task
	 * 
	 * @param dependent Task to add as dependent
	 */
	public synchronized void withDependent(final @NonNull Task<?, ?> dependent) {
		_dependents
				.add(dependent);
		dependent._dependencies
				.add(this);
	}

	/**
	 * Adds a message update listener `Runnable` to this `Task`, which
	 * {@link Runnable#run() updates} on
	 * each call to {@link #setMessage(String) update message}.
	 * 
	 * @param listener Listener to add as listener
	 */
	public synchronized void withListener(final @NonNull Runnable listener) {
		_listeners.add(listener);
	}

	@Override
	public synchronized void run() {
		ListIterator<Consumes> iterableData = null;
		boolean lastOk = true;
		while (!_completable.isDone()) {
			try {
				State state = _state.get();
				switch (state) {
					case CREATED -> {
						setState(State.AWAITING_DEPENDENCIES);
						break;
					}

					case AWAITING_DEPENDENCIES -> {
						CompletableFuture<?>[] dependents = _dependents.stream()
								.map(Task::completable)
								.toArray(CompletableFuture[]::new);
						CompletableFuture.allOf(dependents).join();
						setState(State.COLLECTING);
						break;
					}

					case COLLECTING -> {
						iterableData = collect().listIterator();
						setState(State.OPERATING);
						break;
					}

					case OPERATING -> {
						if (!iterableData.hasNext()) {
							setState(State.COMPLETED);
							break;
						}
						result = operate(operand = iterableData.next());
						setState(State.PRODUCING_RESULT);
						break;
					}

					case PRODUCING_RESULT -> {
						boolean ok = result(result);
						if (!lastOk && ok) {
							_attempt.set(0);
						}
						if (!ok) {
							setState(State.RETRYING);
						} else {
							setState(State.OPERATING);
						}
						lastOk = ok;
						break;
					}

					case RETRYING -> {
						final int currentAttempt = _attempt.getAndIncrement();
						if (currentAttempt >= MAXIMUM_OPERAND_RETRIES) {
							iterableData.previous();
							setState(State.OPERATING);
						} else {
							setState(State.PRODUCING_RESULT);
						}
						break;
					}

					case RESTARTING -> {
						restart();
						setState(State.AWAITING_DEPENDENCIES);
						_attempt.set(0);
						break;
					}

					case COMPLETED, FAILED -> {
						_completable.complete(null);
						return;
					}

					default -> {
						_completable.complete(null);
						throw new IllegalStateException("Invalid State Accessed");
					}
				}
			} catch (final Throwable throwable) {
				setState(State.FAILED);
				_completable.completeExceptionally(throwable);
				_logger.severe(String.format("%s [%s] :: %s", getName(), throwable.getMessage()));
				throwable.printStackTrace();
			}
		}
	}

	/**
	 * Modifies (safely) the current state of this `Task`.
	 * 
	 * @param state New state of task
	 * @throws IllegalStateException When modifications are made to a
	 *                               {@link State#FAILED failed} or
	 *                               {@link State#COMPLETED completed} Task
	 */
	protected synchronized void setState(final @NonNull State state) throws IllegalStateException {
		final State currentState = _state.get();
		if (currentState == State.FAILED || currentState == State.COMPLETED) {
			throw new IllegalStateException(
					String.format("%s [%s] :: Illegal State Modification", getName(), state.name()));
		}
		if (state == State.COMPLETED || state == State.FAILED) {
			_completable.complete(null);
		}
		this._state.set(state);
		_logger.fine(String.format("%s [%s] :: State Update", getName(), state.name()));
	}

	/**
	 * Modifies the current status message of this {@link #run() operation} of this
	 * `Task`
	 * with a descriptive message.
	 * 
	 * @param message Descriptive message of current operation of this Task
	 */
	protected synchronized void setMessage(final @NonNull String message) {
		this.message = message;
		_listeners.forEach(Runnable::run);
	}

	/**
	 * Provides the name of this `Task`.
	 * 
	 * @return Name of this task
	 */
	public String getName() {
		return _name;
	}

	/**
	 * Provides the state of this `Task`.
	 * 
	 * @return Current state of this task
	 */
	public State getState() {
		return _state.get();
	}

	/**
	 * Provides the most recent consumed operand
	 * 
	 * @return Previous consumed operand
	 */
	public Consumes getConsumed() {
		return operand;
	}

	/**
	 * Provides the most recent produced operand
	 * 
	 * @return Previous produced operand
	 */
	public Produces getProduced() {
		return result;
	}

	/**
	 * Provides a formatted status of the this `Task` with the Name, State, and
	 * {@link #update() Message}.
	 * 
	 * @return Formatted status message
	 */
	public String getReport() {
		return String.format("%s [%s] :: %s", getName(), getState(), message);
	}
}
