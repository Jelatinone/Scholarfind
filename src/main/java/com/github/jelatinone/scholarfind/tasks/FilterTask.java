package com.github.jelatinone.scholarfind.tasks;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.jelatinone.scholarfind.agent.AgentHandler;
import com.github.jelatinone.scholarfind.json.JsonHandler;
import com.github.jelatinone.scholarfind.json.JsonSerializer;
import com.github.jelatinone.scholarfind.meta.State;
import com.github.jelatinone.scholarfind.meta.Task;
import com.github.jelatinone.scholarfind.models.AnnotateDocument;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.AnnotateStub;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.EducationLevel;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.PursuedDegreeLevel;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.Supplement;
import com.github.jelatinone.scholarfind.models.AgentType;

import lombok.NonNull;
import lombok.experimental.NonFinal;

public class FilterTask extends Task<JsonNode, JsonNode> {
    static String DEFAULT_AGENT_PROMPT = """

            """;
    static Options _config = new Options();
    static Logger _logger = Logger.getLogger(AnnotateTask.class.getName());
    static CommandLineParser _parser = new DefaultParser();
    static JsonSerializer<AnnotateDocument> _serializer = (generator, document) -> {
        final AnnotateStub stub = document.stub();

        generator.writeStartObject();

        generator.writeStringField("name", stub.scholarshipTitle());
        generator.writeStringField("url", document.domain().toString());

        if (stub.award() != null) {
            generator.writeNumberField("award", stub.award());
        } else {
            generator.writeNullField("award");
        }

        generator.writeStringField(
                "open",
                stub.open().toString());
        generator.writeStringField(
                "close",
                stub.close().toString());

        generator.writeFieldName("pursued");
        generator.writeStartArray();
        for (PursuedDegreeLevel value : stub.pursued()) {
            generator.writeString(value.name());
        }
        generator.writeEndArray();

        generator.writeFieldName("education");
        generator.writeStartArray();
        for (EducationLevel value : stub.education()) {
            generator.writeString(value.name());
        }
        generator.writeEndArray();

        generator.writeFieldName("supplements");
        generator.writeStartArray();
        for (Supplement value : stub.supplements()) {
            generator.writeString(value.name());
        }
        generator.writeEndArray();

        generator.writeFieldName("requirements");
        generator.writeStartArray();
        for (String value : stub.requirements()) {
            generator.writeString(value);
        }
        generator.writeEndArray();

        generator.writeEndObject();
    };

    @NonFinal
    JsonHandler<AnnotateDocument> handler;
    @NonFinal
    AgentHandler<Boolean> agent;

    @NonFinal
    String source;
    @NonFinal
    String destination;
    @NonFinal
    Integer timeout;
    @NonFinal
    AgentType type;

    static {
        _config.addOptions(DEFAULT_OPTION_CONFIGURATION);
        Option opt_networkTimeout = Option.builder()
                .longOpt("timeout")
                .numberOfArgs(1)
                .hasArg()
                .valueSeparator('=')
                .desc("maximum time (seconds) to wait for a network request")
                .converter(Integer::valueOf)
                .get();
        _config.addOption(opt_networkTimeout);
        Option opt_agentType = Option.builder()
                .longOpt("agent")
                .numberOfArgs(1)
                .hasArg()
                .valueSeparator('=')
                .desc("type of agent to execute this task with")
                .converter((value) -> AgentType.valueOf(value.toUpperCase()))
                .get();
        _config.addOption(opt_agentType);
    }

    public FilterTask(final @NonNull String... arguments) {
        super("filter");
        withMessage("Initialization started", Level.INFO);
        try {
            CommandLine command = _parser.parse(_config, arguments);
            String sourceTarget = command.getOptionValue("from");
            source = sourceTarget;

            String destinationTarget = command.getOptionValue("to");
            destination = destinationTarget != null ? destinationTarget
                    : String.format(DEFAULT_DESTINATION_LOCATION, getName(), LocalDate
                            .now()
                            .toString());

            Integer networkTimeout = command.getParsedOptionValue("timeout");
            timeout = networkTimeout != null ? networkTimeout : DEFAULT_NETWORK_TIMEOUT;

            AgentType agentType = command.getParsedOptionValue("agent");
            if (agentType == null) {
                String message = "Initialization failed : Failed to retrieve agent type";
                withMessage(message, Level.SEVERE);
                withState(State.FAILED);
                return;
            }
            type = agentType;
            agent = type.acquire(DEFAULT_AGENT_PROMPT, Boolean.class);

            handler = JsonHandler.acquireWriter(destination, _serializer);
        } catch (final ParseException exception) {
            String message = String.format("Initialization failed : Failed to parse arguments %s",
                    exception.getMessage());
            withMessage(message, Level.SEVERE);
            withState(State.FAILED);
            return;
        } catch (final IOException exception) {
            String message = String.format("Initialization failed : Failed to create JSON handler",
                    exception.getMessage());
            withMessage(message, Level.SEVERE);
            withState(State.FAILED);
            return;
        }
        withMessage("Initialization complete", Level.INFO);
    }

    protected synchronized List<JsonNode> collect() {
        withMessage("Collection started", Level.INFO);

        File file = new File(source);
        ObjectMapper mapper = new ObjectMapper();

        List<JsonNode> items = new ArrayList<>();
        try {
            withMessage("Collection acquired", Level.INFO);
            ArrayNode results = JsonHandler.acquireContent(file, mapper);
            results.forEach(items::add);
            withMessage(String.format("Found %d URLs", items.size()), Level.INFO);
        } catch (final IOException exception) {
            String message = String.format("Failed to retrieve source content: %s", source);
            withMessage(message, Level.SEVERE);
            withState(State.FAILED);
        }
        withMessage("Collection complete", Level.INFO);
        return items;
    }

    @Override
    public synchronized void close() throws IOException {
        withMessage("Closing resources", Level.INFO);
        try {
            agent.close();
            handler.close();
        } catch (final IOException exception) {
            String message = "Closing resources failed";
            withMessage(message, Level.SEVERE);
            throw exception;
        }
        withMessage("Close resources safely completed", Level.INFO);
    }

    @Override
    protected synchronized JsonNode operate(final @NonNull JsonNode operand) {
        String textContent = operand.asText();
        Boolean estimate = agent.annotate(textContent);
        return estimate? operand: null;
    }

    @Override
    protected synchronized boolean result(final JsonNode operand) {
        if (operand != null) {
			try {
                handler.writeDocument(operand);
				return true;
			} catch (final IOException exception) {
				String message = String.format("Failed to write annotate document : %s", exception.getMessage());
				withMessage(message, Level.SEVERE);
				return false;
			}
		}
		String message = "Failed to write annotate document: operand was null";
		withMessage(message, Level.SEVERE);
		return false;
    }

	@Override
	protected synchronized void restart() throws IOException {
		withMessage("Restarting resources", Level.INFO);
		try {
			agent.close();
			agent = type.acquire(DEFAULT_AGENT_PROMPT, Boolean.class);

			handler.close();
			handler = JsonHandler.acquireWriter(destination, _serializer);
		} catch (final IOException exception) {
			String message = "Restarting resources safely failed";
			withMessage(message, Level.SEVERE);
			throw exception;
		}
		withMessage("Restarting resources safely completed", Level.INFO);
	}
}
