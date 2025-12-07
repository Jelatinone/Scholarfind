package com.github.jelatinone.scholarfind.agent;

import java.io.Closeable;

import lombok.NonNull;

public interface AgentHandler<Stub> extends Closeable {

	public abstract Stub annotate(final @NonNull String page);
}
