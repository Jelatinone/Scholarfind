package com.github.jelatinone.scholarfind.agent.implementation;

import java.io.IOException;
import java.util.logging.Logger;

import com.github.jelatinone.scholarfind.agent.AgentHandler;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.StructuredChatCompletion.Choice;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OpenAIAgentHandler<Stub> implements AgentHandler<Stub> {

	static String DEFAULT_ENV_API_KEY = "OPENAI_API_KEY";

	static Logger _logger = Logger.getLogger(OpenAIAgentHandler.class.getName());

	String prompt;
	OpenAIClient client;

	Class<Stub> responseFormat;

	public OpenAIAgentHandler(final String prompt, Class<Stub> responseFormat) {
		this.prompt = prompt;
		this.responseFormat = responseFormat;

		client = OpenAIOkHttpClient.builder()
				.apiKey(System.getenv(
						DEFAULT_ENV_API_KEY))
				.build();
	}

	@Override
	public synchronized void close() throws IOException {
		synchronized (client) {
			client.close();
		}
	}

	@Override
	public Stub annotate(@NonNull String content) {
		StructuredChatCompletionCreateParams<Stub> params = ChatCompletionCreateParams.builder()
				.addSystemMessage(prompt)
				.addUserMessage(content)
				.model(ChatModel.O4_MINI)
				.responseFormat(responseFormat)
				.build();
		StructuredChatCompletion<Stub> completion;
		synchronized (client) {
			completion = client.chat()
					.completions()
					.create(params);
		}
		Choice<Stub> choice = completion.choices().get(0);
		Stub stub = choice.message()
				.content()
				.orElse(null);
		return stub;
	}
}