package com.github.jelatinone.scholarfind.meta;

/**
 * 
 * <h1>State</h1>
 * 
 * <p>
 * Describes the State of a given {@link Task} at a given point during operation
 * <p>
 * 
 * @author Cody Washington
 */
public enum State {

	CREATED,

	AWAITING_DEPENDENCIES,

	COLLECTING,

	OPERATING,

	PRODUCING_RESULT,

	RETRYING,

	RESTARTING,

	COMPLETED,

	FAILED
}