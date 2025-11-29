package com.github.jelatinone.scholarfind.agent;

import java.io.Closeable;

import org.htmlunit.html.HtmlPage;

import lombok.NonNull;

public interface AgentHandler<Stub> extends Closeable {

	public abstract Stub annotate(final @NonNull HtmlPage page);
}
