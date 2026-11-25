package com.atlassian.oai.validator.schema.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Restores schema references that were rewritten while the API definition was parsed.
 * <p>
 * When a schema is read into the Swagger model, a {@code $ref} value that is not a fragment and
 * contains neither a {@code '.'} nor a {@code '/'} is assumed to name an OpenAPI component and is
 * rewritten to {@code #/components/schemas/<value>} (see {@code Schema.set$ref} in swagger-core).
 * That assumption is wrong for plain JSON Schema references such as {@code "list"},
 * {@code "child1#my_anchor"} or a URN, which must instead be resolved against the active base URI
 * (established by {@code $id}) when the schema is validated. The rewritten reference names no
 * component, so validation fails with an unresolvable reference.
 * <p>
 * This transformer walks the converted schema and, for every {@code $ref} or {@code $dynamicRef}
 * whose value has the {@code #/components/schemas/} prefix but does not name an actual component
 * schema, restores the reference as it was written in the source document. References that name
 * an actual component, and references containing a {@code '/'} (pointers into a component), are
 * left untouched.
 */
public class ReferenceResolutionTransformer extends SchemaTransformer {

    private static final ReferenceResolutionTransformer INSTANCE = new ReferenceResolutionTransformer();

    private static final String COMPONENTS_SCHEMAS_REF_PREFIX = "#/components/schemas/";
    private static final String REF_FIELD = "$ref";
    private static final String DYNAMIC_REF_FIELD = "$dynamicRef";

    /**
     * Fields whose values are instance data rather than schemas. References appearing inside
     * them are data, not schema references, and must not be rewritten.
     */
    private static final Set<String> NON_SCHEMA_FIELDS = Set.of("default", "const", "enum", "example", "examples");

    public static ReferenceResolutionTransformer getInstance() {
        return INSTANCE;
    }

    @Override
    public void apply(final JsonNode schemaObject, final SchemaTransformationContext context) {
        final Set<String> componentNames = new HashSet<>();
        context.getSchemaDefinitions().fieldNames().forEachRemaining(componentNames::add);
        restoreReferences(schemaObject, componentNames);
    }

    private void restoreReferences(final JsonNode node, final Set<String> componentNames) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(element -> restoreReferences(element, componentNames));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        final ObjectNode objectNode = (ObjectNode) node;
        restoreReference(objectNode, REF_FIELD, componentNames);
        restoreReference(objectNode, DYNAMIC_REF_FIELD, componentNames);
        final List<String> fieldNames = new ArrayList<>();
        objectNode.fieldNames().forEachRemaining(fieldNames::add);
        fieldNames.stream()
            .filter(fieldName -> !NON_SCHEMA_FIELDS.contains(fieldName) && !fieldName.startsWith("x-"))
            .forEach(fieldName -> restoreReferences(objectNode.get(fieldName), componentNames));
    }

    private void restoreReference(final ObjectNode node, final String field, final Set<String> componentNames) {
        final JsonNode ref = node.get(field);
        if (ref == null || !ref.isTextual()) {
            return;
        }
        final String restored = restoreReferenceValue(ref.textValue(), componentNames);
        if (!restored.equals(ref.textValue())) {
            node.put(field, restored);
        }
    }

    private String restoreReferenceValue(final String ref, final Set<String> componentNames) {
        if (!ref.startsWith(COMPONENTS_SCHEMAS_REF_PREFIX)) {
            return ref;
        }
        final String rest = ref.substring(COMPONENTS_SCHEMAS_REF_PREFIX.length());
        if (rest.isEmpty() || rest.contains("/")) {
            return ref;
        }
        final int fragmentStart = rest.indexOf('#');
        final String name = fragmentStart < 0 ? rest : rest.substring(0, fragmentStart);
        return componentNames.contains(name) ? ref : rest;
    }
}
