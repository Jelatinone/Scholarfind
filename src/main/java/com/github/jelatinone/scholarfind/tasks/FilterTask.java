package com.github.jelatinone.scholarfind.tasks;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.github.jelatinone.scholarfind.models.BooleanDocument;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.AnnotateStub;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.EducationLevel;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.PursuedDegreeLevel;
import com.github.jelatinone.scholarfind.models.AnnotateDocument.Supplement;
import com.github.jelatinone.scholarfind.models.AgentType;

import lombok.NonNull;
import lombok.experimental.NonFinal;

public class FilterTask extends Task<JsonNode, BooleanDocument> {
    static String DEFAULT_AGENT_PROMPT = """
        You will receive two JSON objects outside of this prompt:

        1. A scholarship object that strictly follows this schema:

        {
            scholarshipTitle: string,
            organizationName: string,
            award: double | null,
            open: string | null,
            close: string | null,
            pursued: PursuedDegreeLevel[] | [],
            education: EducationLevel[] | [],
            supplements: Supplement[] | [],
            requirements: string[] | []
        }

        2. A student profile object that may include eligibility attributes
        (GPA, demographics, major, citizenship, etc.) as well as optional
        student-specific preferences (such as minimum award amounts, preferred
        locations, etc.).

        Your task is to determine whether the student qualifies for this scholarship.

        Output Rules:
        - Return ONLY `true` or `false` (lowercase).
        - Consider **only** requirements that relate to eligibility unless the
        student profile explicitly specifies additional preference constraints
        (e.g., “minimumAward: 25000”).
        - If a scholarship requirement is not met, return `false`.
        - If the student profile includes preference-based constraints
        (e.g., minimum award, region, field of study, modality), those must
        also be satisfied or the result is `false`.
        - If the student profile does NOT include a preference for a given
        attribute, you must IGNORE that attribute entirely unless the scholarship
        explicitly requires it.
        - If a requirement is ambiguous, missing, or cannot be validated from the
        scholarship object, treat it as **not required**.

        Evaluation Guidelines:
        - For enum arrays (`pursued`, `education`, `supplements`):
            - If non-empty, the student must have at least one matching value.
        - For textual `requirements`:
            - Evaluate each requirement literally using only fields present in the student profile.
            - Examples: GPA thresholds, citizenship restrictions, demographic qualifiers, age limits, majors, financial-need wording, enrollment status, etc.
        - For profile-based preferences:
            - Apply them only if the profile explicitly provides them.
            - Example: If the profile specifies "minimumAward: 25000", reject scholarships with award < 25000 or award = null.
        - Ignore factors such as award size, deadlines, geography, or institutional type unless:
            * they are required in the scholarship, OR
            * the student profile explicitly includes them as a preference.

        Return exactly one value: `true` if all scholarship requirements AND all
        profile-specified preferences are met; otherwise return `false`.

        Student profile: 

        --BEGIN PROFILE--
        %s
        --END PROFILE--
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
    AgentHandler<BooleanDocument> agent;

    @NonFinal
    String source;
    @NonFinal
    String destination;
    @NonFinal 
    String profile;

    @NonFinal
    AgentType type;

    static {
        _config.addOptions(DEFAULT_OPTION_CONFIGURATION);
        Option opt_agentType = Option.builder()
                .longOpt("agent")
                .numberOfArgs(1)
                .hasArg()
                .valueSeparator('=')
                .desc("type of agent to execute this task with")
                .converter((value) -> AgentType.valueOf(value.toUpperCase()))
                .get();
        _config.addOption(opt_agentType);
		Option opt_profileTarget = Option.builder()
				.longOpt("profile")
				.required()
				.hasArg()
				.desc("location to pull profile source data from > ~2GB")
				.get();
		_config.addOption(opt_profileTarget);
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

            String profileTarget = command.getOptionValue("profile");
            if(profileTarget == null) {
                String message = "Initialization failed : Failed to retrieve profile";
                withMessage(message, Level.SEVERE);
                withState(State.FAILED);
                return;
            }

            AgentType agentType = command.getParsedOptionValue("agent");
            if (agentType == null) {
                String message = "Initialization failed : Failed to retrieve agent type";
                withMessage(message, Level.SEVERE);
                withState(State.FAILED);
                return;
            }
            type = agentType;

            String profileContent = Files.readString(Path.of(profileTarget));
            agent = type.acquire(String.format(DEFAULT_AGENT_PROMPT, profileContent), BooleanDocument.class);

            handler = JsonHandler.acquireWriter(destination, _serializer);
        } catch (final ParseException exception) {
            String message = String.format("Initialization failed : Failed to parse arguments %s",
                    exception.getMessage());
            withMessage(message, Level.SEVERE);
            withState(State.FAILED);
            return;
        } catch (final IOException exception) {
            String message = String.format("Initialization failed : Failed to handle files",
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
            withMessage(String.format("Found %d Items", items.size()), Level.INFO);
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
    protected synchronized BooleanDocument operate(final @NonNull JsonNode operand) {
        withMessage("Annotating content", Level.INFO);
        String textContent = operand.asText();
        BooleanDocument annotation = agent.annotate(textContent);
        if(annotation == null) {
            String message = String.format("Agent failed to create document");
			withMessage(message, Level.SEVERE);
        }
        return annotation;
    }

    @Override
    protected synchronized boolean result(final BooleanDocument operand) {
        Boolean annotation = operand.value();
        JsonNode node = getConsumed();
        if(node == null) {
            String message = "Failed to write annotate document: operand was null";
            withMessage(message, Level.SEVERE);
            return false;            
        }
        if(annotation) {
            try {
                handler.writeDocument(node);
                return true;
            } catch (final IOException exception) {
                String message = String.format("Failed to write annotate document : %s", exception.getMessage());
                withMessage(message, Level.SEVERE);
                return false;
            }            
        }
        String message = "Skip to write annotate document: operand was false";
        withMessage(message, Level.SEVERE);
        return true;
    }

	@Override
	protected synchronized void restart() throws IOException {
		withMessage("Restarting resources", Level.INFO);
		try {
			agent.close();
			agent = type.acquire(DEFAULT_AGENT_PROMPT, BooleanDocument.class);

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
