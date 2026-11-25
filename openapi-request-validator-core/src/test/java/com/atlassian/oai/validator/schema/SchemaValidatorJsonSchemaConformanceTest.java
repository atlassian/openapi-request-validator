package com.atlassian.oai.validator.schema;

import com.atlassian.oai.validator.report.MessageResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

import static com.atlassian.oai.validator.util.ValidatorTestUtil.assertPass;

/**
 * Conformance tests based on cases from the JSON-Schema-Test-Suite that were reported as
 * mishandled in <a href="https://github.com/atlassian/openapi-request-validator/issues/44">issue #44</a>.
 * <p>
 * Each case schema is embedded as a component schema of an OpenAPI 3.1 document and the test
 * data is validated against it, mirroring how such schemas appear in real API definitions.
 */
public class SchemaValidatorJsonSchemaConformanceTest {

    // JSON-Schema-Test-Suite draft2020-12/anchor.json, group 3, test 0:
    // the $ref "child1#my_anchor" must resolve against the active base URI ($id).
    private static final String ANCHOR_2020_12 =
        "{\"$schema\": \"https://json-schema.org/draft/2020-12/schema\", "
            + "\"$id\": \"http://localhost:1234/draft2020-12/foobar\", "
            + "\"$defs\": {\"A\": {\"$id\": \"child1\", \"allOf\": ["
            + "{\"$id\": \"child2\", \"$anchor\": \"my_anchor\", \"type\": \"number\"}, "
            + "{\"$anchor\": \"my_anchor\", \"type\": \"string\"}]}}, "
            + "\"$ref\": \"child1#my_anchor\"}";

    // JSON-Schema-Test-Suite draft2020-12/dynamicRef.json, group 26, test 1:
    // the $ref "list" must resolve against the active base URI ($id), and the $dynamicRef in the
    // referenced resource must follow dynamic scope ("bookending").
    private static final String DYNAMIC_REF_2020_12 =
        "{\"$id\": \"https://test.json-schema.org/dynamicRef-without-anchor/root\", "
            + "\"$ref\": \"list\", "
            + "\"$defs\": {"
            + "\"foo\": {\"$dynamicAnchor\": \"items\", \"type\": \"string\"}, "
            + "\"list\": {\"$id\": \"list\", \"type\": \"array\", "
            + "\"items\": {\"$dynamicRef\": \"#/$defs/items\"}, "
            + "\"$defs\": {\"items\": {\"$comment\": \"bookending\", \"$dynamicAnchor\": \"items\", \"type\": \"number\"}}}}}";

    @Test
    public void validate_anchorRefAgainstBaseUri_shouldPass() {
        assertPass(validate(ANCHOR_2020_12, "\"a\""));
    }

    @Test
    public void validate_dynamicRefWithoutAnchor_shouldPass() {
        assertPass(validate(DYNAMIC_REF_2020_12, "[24, 42]"));
    }

    private ValidationReport validate(final String schemaJson, final String data) {
        final OpenAPI api = new OpenAPIParser()
            .readContents(spec(schemaJson), null, new ParseOptions())
            .getOpenAPI();
        final Schema<?> schema = api.getComponents().getSchemas().get("Case");
        return new SchemaValidator(api, new MessageResolver()).validate(data, schema, "request.body");
    }

    private String spec(final String schemaJson) {
        return "{\"openapi\": \"3.1.0\", \"info\": {\"title\": \"t\", \"version\": \"1\"}, "
            + "\"paths\": {\"/x\": {\"post\": {\"requestBody\": {\"content\": {\"application/json\": "
            + "{\"schema\": {\"$ref\": \"#/components/schemas/Case\"}}}}, "
            + "\"responses\": {\"200\": {\"description\": \"ok\"}}}}}, "
            + "\"components\": {\"schemas\": {\"Case\": " + schemaJson + "}}}";
    }
}
