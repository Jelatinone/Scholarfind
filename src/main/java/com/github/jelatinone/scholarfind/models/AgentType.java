package com.github.jelatinone.scholarfind.models;

import java.io.IOException;

import com.github.jelatinone.scholarfind.agent.AgentHandler;
import com.github.jelatinone.scholarfind.agent.implementation.OpenAIAgentHandler;

import lombok.NonNull;

public enum AgentType {
	CHAT_GPT {
		@Override
		public <Type> AgentHandler<Type> acquire(final @NonNull String prompt, final @NonNull Class<Type> type) {
			return new OpenAIAgentHandler<Type>(prompt, type);
		}
	},

	NONE {
		@Override
		public <Type> AgentHandler<Type> acquire(final @NonNull String prompt, final @NonNull Class<Type> type) {
			return new AgentHandler<Type>() {

				@Override
				public void close() throws IOException {
				}

				@Override
				public Type annotate(@NonNull String page) {
					return null;
				}

			};
		}
	};

	public abstract <Type> AgentHandler<Type> acquire(final @NonNull String prompt, final @NonNull Class<Type> type);
}