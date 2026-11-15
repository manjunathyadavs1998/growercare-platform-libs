package com.growercare.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * SPEC-PLAT-01 R1/R3: reads a service's generated OpenAPI document and exports it, pretty-printed with sorted keys
 * (stable diffs), to {@code gc-backend/contracts/<service>.openapi.json}. CI runs {@code git diff --exit-code contracts/}.
 */
public final class OpenApiContract {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private OpenApiContract() { }

    public static JsonNode parse(String openApiJson) {
        return JSON.readTree(openApiJson);
    }

    /** True when {@code paths[path][method]} exists (method lower-case, e.g. "get"). */
    public static boolean hasOperation(JsonNode api, String path, String method) {
        var node = api.path("paths").path(path).path(method.toLowerCase(java.util.Locale.ROOT));
        return !node.isMissingNode();
    }

    /** Writes the contract with sorted keys; the module runs with cwd = its own directory (services/<svc>). */
    public static Path export(JsonNode api, String serviceName) {
        var target = Path.of("..", "..", "contracts", serviceName + ".openapi.json").normalize();
        try {
            Files.createDirectories(target.getParent());
            var sorted = JSON.readTree(JSON.writeValueAsString(JSON.treeToValue(api, Object.class)));
            Files.writeString(target, JSON.writeValueAsString(sorted) + System.lineSeparator(), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
