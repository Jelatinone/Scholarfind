package com.github.jelatinone.scholarfind.models;

import com.github.jelatinone.scholarfind.agent.AgentHandler;
import com.github.jelatinone.scholarfind.agent.implementation.OpenAIAgentHandler;

import lombok.NonNull;

public enum AgentType {
		CHAT_GPT {
			@Override
			public <Type> AgentHandler<Type> acquire(final @NonNull String prompt, final @NonNull Class<Type> type) {
				return new OpenAIAgentHandler<Type>(prompt, type);
			}
		};

		public abstract <Type> AgentHandler<Type> acquire(final @NonNull String prompt, final @NonNull Class<Type> type);
}