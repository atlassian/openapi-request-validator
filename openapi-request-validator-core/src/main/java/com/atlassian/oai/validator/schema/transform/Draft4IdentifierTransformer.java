package com.atlassian.oai.validator.schema.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Translates draft-4 style schema identifiers in schemas validated with the 2020-12 dialect.
 * <p>
 * Draft-4 schemas identify themselves and their subschemas with the {@code id} keyword and group
 * reusable subschemas under {@code definitions}. Neither is understood by the 2020-12 dialect used
 * for OpenAPI 3.1 documents: an {@code id} makes schema construction fail, and references to the
 * identifiers (for example a URN) cannot resolve. Such schemas still appear in 3.1 documents when
 * definitions are migrated from older drafts.
 * <p>
 * This transformer rewrites, for every schema object in the converted schema:
 * <ul>
 * <li>an {@code id} holding an absolute URI to {@code $id} with the same value;</li>
 * <li>an {@code id} holding only a fragment (for example {@code "#something"}), or the fragment
 * part of an {@code id} holding a URI with a fragment, to {@code $anchor};</li>
 * <li>a {@code definitions} object to {@code $defs}, merging into an existing {@code $defs}
 * without overwriting its entries.</li>
 * </ul>
 * An existing {@code $id} or {@code $anchor} is never overwritten. The transformation only runs
 * for OpenAPI 3.1 documents; the draft-4 dialect used for OpenAPI 3.0 documents understands
 * {@code id} and {@code definitions} natively.
 */
public class Draft4IdentifierTransformer extends SchemaTransformer {

    private static final Draft4IdentifierTransformer INSTANCE = new Draft4IdentifierTransformer();

    private static final String ID_FIELD = "id";
    private static final String SCHEMA_ID_FIELD = "$id";
    private static final String ANCHOR_FIELD = "$anchor";
    private static final String DEFINITIONS_FIELD = "definitions";
    private static final String DEFS_FIELD = "$defs";

    /**
     * Fields whose values are instance data rather than schemas. Identifiers appearing inside
     * them are data, not schema identifiers, and must not be translated.
     */
    private static final Set<String> NON_SCHEMA_FIELDS = Set.of("default", "const", "enum", "example", "examples");

    public static Draft4IdentifierTransformer getInstance() {
        return INSTANCE;
    }

    @Override
    public void apply(final JsonNode schemaObject, final SchemaTransformationContext context) {
        if (context.isOpenApi30()) {
            return;
        }
        translateIdentifiers(schemaObject);
    }

    private void translateIdentifiers(final JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(this::translateIdentifiers);
            return;
        }
        if (!node.isObject()) {
            return;
        }
        final ObjectNode objectNode = (ObjectNode) node;
        translateId(objectNode);
        translateDefinitions(objectNode);
        final List<String> fieldNames = new ArrayList<>();
        objectNode.fieldNames().forEachRemaining(fieldNames::add);
        fieldNames.stream()
            .filter(fieldName -> !NON_SCHEMA_FIELDS.contains(fieldName) && !fieldName.startsWith("x-"))
            .forEach(fieldName -> translateIdentifiers(objectNode.get(fieldName)));
    }

    private void translateId(final ObjectNode node) {
        final JsonNode id = node.get(ID_FIELD);
        if (id == null || !id.isTextual()) {
            return;
        }
        final String value = id.textValue();
        final int fragmentStart = value.indexOf('#');
        final String base = fragmentStart < 0 ? value : value.substring(0, fragmentStart);
        final String fragment = fragmentStart < 0 ? "" : value.substring(fragmentStart + 1);
        if (!base.isEmpty() && !node.has(SCHEMA_ID_FIELD)) {
            node.put(SCHEMA_ID_FIELD, base);
        }
        if (!fragment.isEmpty() && !node.has(ANCHOR_FIELD)) {
            node.put(ANCHOR_FIELD, fragment);
        }
        node.remove(ID_FIELD);
    }

    private void translateDefinitions(final ObjectNode node) {
        final JsonNode definitions = node.get(DEFINITIONS_FIELD);
        if (definitions == null || !definitions.isObject()) {
            return;
        }
        final JsonNode defs = node.get(DEFS_FIELD);
        if (defs == null || !defs.isObject()) {
            node.set(DEFS_FIELD, definitions);
        } else {
            final ObjectNode defsObject = (ObjectNode) defs;
            definitions.fields().forEachRemaining(entry -> {
                if (!defsObject.has(entry.getKey())) {
                    defsObject.set(entry.getKey(), entry.getValue());
                }
            });
        }
        node.remove(DEFINITIONS_FIELD);
    }
}
