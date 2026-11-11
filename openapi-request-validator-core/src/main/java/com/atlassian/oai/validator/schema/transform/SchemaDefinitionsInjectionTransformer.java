package com.atlassian.oai.validator.schema.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        final Set<String> referenced = referencedDefinitions(schemaObject, definitions);
        // In the order they are declared, so the injected block does not depend on traversal order.
        for (final Map.Entry<String, JsonNode> definition : definitions.properties()) {
            if (referenced.contains(definition.getKey())) {
                injected.set(definition.getKey(), definition.getValue().deepCopy());
            }
        }
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
                reference(definitionName(node.textValue()), definitions, referenced, pending);
            }
        }
        return referenced;
    }

    // A reference can point inside a definition, e.g. #/components/schemas/Order/properties/customer
    private static String definitionName(final String reference) {
        final int end = reference.indexOf('/', SCHEMA_REF_PREFIX.length());
        return end < 0 ? reference.substring(SCHEMA_REF_PREFIX.length()) : reference.substring(SCHEMA_REF_PREFIX.length(), end);
    }

    private static void reference(final String name,
                                  final JsonNode definitions,
                                  final Set<String> referenced,
                                  final Deque<JsonNode> pending) {
        final String key = definitions.has(name) ? name : decoded(name);
        final JsonNode definition = definitions.get(key);
        if (definition != null && referenced.add(key)) {
            pending.push(definition);
        }
    }

    // A reference is a URI fragment holding a JSON Pointer, so it can percent-encode the name and escape '~' and '/' in it.
    private static String decoded(final String name) {
        try {
            return URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8).replace("~1", "/").replace("~0", "~");
        } catch (final IllegalArgumentException e) {
            return name;
        }
    }
}
