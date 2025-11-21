package com.github.jelatinone.scholarfind.meta;

import java.io.Closeable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

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
@FieldDefaults(level = AccessLevel.PROTECTED)
public abstract class Task<Consumes extends @NonNull Serializable, Produces extends @NonNull Serializable>
		implements Runnable, Closeable {
	final static Logger _logger = Logger.getLogger(Task.class.getName());
	final static Integer _maxRetry = 3;

	final String _name;

	final Collection<Task<?, ?>> _dependencies;
	final Collection<Task<?, ?>> _dependents;

	final AtomicReference<State> _state;
	final CompletableFuture<Void> _completable;

	Consumes operand = null;
	Produces result = null;

	AtomicInteger attempt;

	/**
	 * Creates a new abstract Task
	 * 
	 * @param name Name of the task to be created
	 */
	public Task(final @NonNull String name) {
		_name = name;

		attempt = new AtomicInteger();
		_state = new AtomicReference<State>(State.CREATED);
		_completable = new CompletableFuture<>();

		_dependencies = new ArrayList<>();
		_dependents = new ArrayList<>();

		_logger.fine(String.format("Created new Task :: %s", getName()));
	}

	/**
	 * Collects all consumable data into a single collection for
	 * {@link #operate(Serializable) operation} to be performed on each element
	 * within the collection.
	 * 
	 * @return Collection of consumable data
	 */
	protected abstract List<Consumes> collect();

	/**
	 * Performs an operation on `consumable` data and maps to a `producible` a
	 * result.
	 * 
	 * @param operand Data to be mapped
	 * @return Mapped result
	 */
	protected abstract Produces operate(final Consumes operand);

	/**
	 * Self-callback function to determine the validity of the resulting data
	 * 
	 * @param operand Data to be checked
	 * @return Mapped result
	 */
	protected abstract boolean result(final Produces operand);

	/**
	 * Gives the current status of the {@link #run() operation} of this `Task` as a
	 * descriptive message.
	 * 
	 * @return Descriptive message of current operation
	 */
	protected abstract String update();

	/**
	 * Returns a formatted status of the this `Task` with the Name, State, and
	 * {@link #update() Message}.
	 * 
	 * @return Formatted status message
	 */
	public String report() {
		return String.format("%s :: %s", _name, update());
	}

	/**
	 * Provides the {@link CompletableFuture Future} of this `Task`
	 * 
	 * @apiNote This should be used to await the dependencies of the given task
	 *          using {@link CompletableFuture#join() join()}.
	 * 
	 * @return Completable future of the current operation
	 */
	public CompletableFuture<Void> on() {
		return _completable;
	}

	/**
	 * Adds a dependent `Task` to this `Task`, which must be completed
	 * <em>before<em> running any {@link #operate(Serializable) operations} on the
	 * {@link #collect() data} of this task
	 * 
	 * @param dependent Task to depend on
	 */
	public synchronized void with(final Task<?, ?> dependent) {
		_dependents
				.add(dependent);
		dependent._dependencies
				.add(this);
	}

	/**
	 * Modifies (safely) the current state of this `Task`.
	 * 
	 * @param state New state of task
	 * @throws IllegalStateException When modifications are made to a
	 *                               {@link State#FAILED failed} or
	 *                               {@link State#COMPLETED completed} Task
	 */
	private synchronized void modify(final @NonNull State state) throws IllegalStateException {
		final State currentState = _state.get();
		if (currentState == State.FAILED || currentState == State.COMPLETED) {
			throw new IllegalStateException(
					String.format("Cannot modify the state of a %s Task!", currentState.name().toLowerCase()));
		}
		this._state.set(state);
		_logger.fine(String.format("[%s] State Update :: %s", getName(), state.name()));
	}

	@Override
	public synchronized void run() {
		try {
			ListIterator<Consumes> collectedData = null;
			boolean lastOk = true;
			while (!_completable.isDone()) {
				switch (_state.get()) {
					case CREATED -> {
						modify(State.COLLECTING);
					}

					case COLLECTING -> {
						try {
							collectedData = collect().listIterator();
							modify(State.OPERATING);
						} catch (final Exception exception) {
							modify(State.FAILED);
						}
					}

					case OPERATING -> {
						if (!collectedData.hasNext()) {
							modify(State.COMPLETED);
							break;
						}
						try {
							result = operate(operand = collectedData.next());
							modify(State.PRODUCING_RESULT);
						} catch (final Exception exception) {
							modify(State.FAILED);
						}
					}

					case PRODUCING_RESULT -> {
						try {
							boolean ok = result(result);
							if (!lastOk && ok) {
								attempt.set(0);
							}
							if (!ok) {
								modify(State.RETRYING);
							} else {
								modify(State.OPERATING);
							}
							lastOk = ok;
						} catch (Exception e) {
							modify(State.FAILED);
						}
					}

					case RETRYING -> {
						final int currentAttempt = attempt.get();
						if (currentAttempt >= _maxRetry) {
							attempt.incrementAndGet();
							collectedData.previous();
							modify(State.OPERATING);
						}
						modify(State.FAILED);
					}

					case RESTARTING -> {
						modify(State.COLLECTING);
					}

					case COMPLETED, FAILED -> {
						_completable.complete(null);
						return;
					}

					default -> {
						_completable.complete(null);
						throw new UnsupportedOperationException("Invalid State!");
					}
				}
			}
		} catch (final Exception exception) {
			_logger.fine(String.format("[%s] Critical Failure :: %s", getName(), exception.getMessage()));
			exception.printStackTrace();
		}
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
}
