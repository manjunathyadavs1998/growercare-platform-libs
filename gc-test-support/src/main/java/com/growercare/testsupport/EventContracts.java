package com.growercare.testsupport;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SPEC-PLAT-04 R54: validates outbox envelopes against {@code gc-backend/contracts/events/<eventType>.schema.json}
 * and checks that {@code data} carries no PII-looking field. Modules run with cwd = their own directory.
 */
public final class EventContracts {

    /** Field-name fragments that must never appear in event data (R54: ids and codes only). */
    static final Set<String> PII_FRAGMENTS = Set.of("name", "phone", "mobile", "email", "address", "body", "text", "dob");

    private static final Path DIR = Path.of("..", "..", "contracts", "events").normalize();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private EventContracts() { }

    /** @return contract violations of one outbox envelope (empty = valid). A missing contract is a violation. */
    public static List<String> violations(String eventType, String envelopeJson) {
        var file = DIR.resolve(eventType + ".schema.json");
        if (!Files.exists(file)) {
            return List.of("no contract " + file);
        }
        var envelope = JSON.readTree(envelopeJson);
        var problems = new ArrayList<String>();
        SCHEMAS.computeIfAbsent(eventType, t -> load(file)).validate(envelope)
                .forEach(error -> problems.add(error.toString()));
        problems.addAll(piiFields(envelope.path("data")));
        return problems;
    }

    static List<String> piiFields(JsonNode data) {
        var problems = new ArrayList<String>();
        data.propertyNames().forEach(field -> {
            var lower = field.toLowerCase(Locale.ROOT);
            PII_FRAGMENTS.stream().filter(lower::contains)
                    .forEach(fragment -> problems.add("PII-looking field '" + field + "' in event data"));
        });
        return problems;
    }

    private static Schema load(Path file) {
        try {
            return REGISTRY.getSchema(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
