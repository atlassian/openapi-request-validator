package com.atlassian.oai.validator.schema.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Simple transformer than injects schema definitions into the `#/components/schemas` path so that references resolve correctly.
 * <p>
 * Only the definitions the schema can reach are injected: the ones it refers to, and the ones those refer to in turn.
 * A compiled schema is cached, so every definition injected here is retained for as long as the cache entry lives.
 * Injecting all of them made the retained size grow with (number of schemas validated) x (size of the specification),
 * even when the specification was loaded fully resolved and the schema referred to none of them.
 */
public class SchemaDefinitionsInjectionTransformer extends SchemaTransformer {

    private static final SchemaDefinitionsInjectionTransformer INSTANCE = new SchemaDefinitionsInjectionTransformer();

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final String MAPPING_FIELD = "mapping";

    public static SchemaDefinitionsInjectionTransformer getInstance() {
        return INSTANCE;
    }

    @Override
    public void apply(final JsonNode schemaObject, final SchemaTransformationContext context) {
        if (!(schemaObject instanceof ObjectNode)) {
            return;
        }

        final JsonNode definitions = context.getSchemaDefinitions();
        final ObjectNode injected = ((ObjectNode) schemaObject).putObject(COMPONENTS_FIELD).putObject(SCHEMAS_FIELD);
        if (definitions == null) {
            return;
        }
        // In the order they are declared, so the injected block does not depend on traversal order.
        final Set<String> referenced = referencedDefinitions(schemaObject, definitions);
        definitions.fields().forEachRemaining(definition -> {
            if (referenced.contains(definition.getKey())) {
                injected.set(definition.getKey(), definition.getValue().deepCopy());
            }
        });
    }

    /**
     * The names of the definitions reachable from the given schema, directly or through other definitions.
     * <p>
     * Any text value that points at a definition counts, not only the value of a {@code $ref}, and so does a
     * discriminator mapping that names a definition without the path.
     */
    private static Set<String> referencedDefinitions(final JsonNode schemaObject, final JsonNode definitions) {
        final Set<String> referenced = new HashSet<>();
        final Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(schemaObject);
        while (!pending.isEmpty()) {
            final JsonNode node = pending.pop();
            if (node.isObject()) {
                for (final Map.Entry<String, JsonNode> field : node.properties()) {
                    if (MAPPING_FIELD.equals(field.getKey()) && field.getValue().isObject()) {
                        field.getValue().forEach(target -> reference(target.asText(), definitions, referenced, pending));
                    }
                    pending.push(field.getValue());
                }
            } else if (node.isArray()) {
                node.forEach(pending::push);
            } else if (node.isTextual() && node.textValue().startsWith(SCHEMA_REF_PREFIX)) {
                reference(node.textValue().substring(SCHEMA_REF_PREFIX.length()), definitions, referenced, pending);
            }
        }
        return referenced;
    }

    private static void reference(final String name,
                                  final JsonNode definitions,
                                  final Set<String> referenced,
                                  final Deque<JsonNode> pending) {
        final JsonNode definition = definitions.get(name);
        if (definition != null && referenced.add(name)) {
            pending.push(definition);
        }
    }
}
