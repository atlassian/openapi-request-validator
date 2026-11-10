package com.atlassian.oai.validator.schema.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SchemaDefinitionsInjectionTransformerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFINITIONS = "{"
            + "\"Order\":{\"type\":\"object\",\"properties\":{\"customer\":{\"$ref\":\"#/components/schemas/Customer\"}}},"
            + "\"Customer\":{\"type\":\"object\",\"properties\":{\"address\":{\"$ref\":\"#/components/schemas/Address\"}}},"
            + "\"Address\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}},"
            + "\"Folder\":{\"type\":\"object\",\"properties\":{\"children\":{\"type\":\"array\","
            + "\"items\":{\"$ref\":\"#/components/schemas/Folder\"}}}},"
            + "\"Cat\":{\"type\":\"object\"},"
            + "\"Dog\":{\"type\":\"object\"},"
            + "\"Unrelated\":{\"type\":\"object\"}"
            + "}";

    private final SchemaDefinitionsInjectionTransformer classUnderTest = SchemaDefinitionsInjectionTransformer.getInstance();

    @Test
    public void injectsNothing_whenSchemaRefersToNoDefinition() throws Exception {
        final ObjectNode schema = schema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");

        classUnderTest.apply(schema, context());

        assertEquals(List.of(), injected(schema));
    }

    @Test
    public void injectsReferencedDefinitions_andTheOnesTheyReferTo() throws Exception {
        final ObjectNode schema = schema("{\"type\":\"array\",\"items\":{\"$ref\":\"#/components/schemas/Order\"}}");

        classUnderTest.apply(schema, context());

        assertEquals(List.of("Order", "Customer", "Address"), injected(schema));
    }

    @Test
    public void injectsSelfReferencingDefinition_once() throws Exception {
        final ObjectNode schema = schema("{\"$ref\":\"#/components/schemas/Folder\"}");

        classUnderTest.apply(schema, context());

        assertEquals(List.of("Folder"), injected(schema));
    }

    @Test
    public void injectsDefinitionsNamedByDiscriminatorMapping_withAndWithoutPath() throws Exception {
        final ObjectNode schema = schema("{\"oneOf\":[{\"type\":\"object\"},{\"type\":\"object\"}],"
                + "\"discriminator\":{\"propertyName\":\"kind\","
                + "\"mapping\":{\"cat\":\"#/components/schemas/Cat\",\"dog\":\"Dog\"}}}");

        classUnderTest.apply(schema, context());

        assertEquals(List.of("Cat", "Dog"), injected(schema));
    }

    @Test
    public void injectsCopies_soTheSharedDefinitionsAreNeverMutated() throws Exception {
        final SchemaTransformationContext context = context();
        final ObjectNode schema = schema("{\"$ref\":\"#/components/schemas/Address\"}");

        classUnderTest.apply(schema, context);
        ((ObjectNode) schema.at("/components/schemas/Address")).put("additionalProperties", false);

        assertTrue(schema.at("/components/schemas/Address").has("additionalProperties"));
        assertFalse(context.getSchemaDefinitions().get("Address").has("additionalProperties"));
    }

    private static List<String> injected(final JsonNode schema) {
        final List<String> names = new ArrayList<>();
        schema.at("/components/schemas").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static ObjectNode schema(final String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }

    private static SchemaTransformationContext context() throws Exception {
        return SchemaTransformationContext.create().withDefinitions(MAPPER.readTree(DEFINITIONS)).build();
    }
}
