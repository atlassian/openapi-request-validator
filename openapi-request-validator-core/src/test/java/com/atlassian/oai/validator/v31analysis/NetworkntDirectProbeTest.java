package com.atlassian.oai.validator.v31analysis;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Probes networknt directly (bypassing this validator's transformer
 * pipeline) to check what it can and can't enforce on its own.
 *
 * Used to isolate whether observed gaps live in:
 *   (a) networknt itself
 *   (b) the validator's transformer pipeline / dispatch logic
 */
public class NetworkntDirectProbeTest {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkntDirectProbeTest.class);

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31());

    private static int validate(final String schemaJson, final String input) throws Exception {
        final var schema = REGISTRY.getSchema(schemaJson);
        final var errs = schema.validate(input, InputFormat.JSON);
        errs.forEach(e -> LOG.error("  - {}", e));
        return errs.size();
    }

    /**
     * Note: in networknt 1.5.x, {@code SchemaValidatorsConfig} is immutable
     * after {@code builder().build()}, and the documented
     * {@code enableUnevaluatedAnalysis()} mutator throws
     * {@code UnsupportedOperationException}. The Builder also doesn't expose
     * the flag. Empirically, unevaluated analysis is automatic when the schema
     * is a valid 2020-12 schema with {@code unevaluatedProperties} declared
     * and the meta-schema chain supports it — see the Deep probe.
     */
    private static int validateWithUnevaluated(final String schemaJson, final String input) throws Exception {
        return validate(schemaJson, input);
    }

    @Test
    void unevaluatedProperties_extra_is_rejected_by_networknt() throws Exception {
        final String schema = "{"
                + "  \"$schema\": \"https://json-schema.org/draft/2020-12/schema\","
                + "  \"allOf\": ["
                + "    {\"type\": \"object\", \"properties\": {\"a\": {\"type\": \"string\"}}, \"additionalProperties\": true},"
                + "    {\"type\": \"object\", \"properties\": {\"b\": {\"type\": \"string\"}}, \"additionalProperties\": true}"
                + "  ],"
                + "  \"unevaluatedProperties\": false"
                + "}";

        // Default config — unevaluated analysis is OFF
        final int errsBadDefault = validate(schema, "{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}");
        LOG.error("[direct/unevaluated extra c, default cfg] errors: {}", errsBadDefault);

        // With unevaluated analysis explicitly enabled
        final int errsBadEnabled = validateWithUnevaluated(schema, "{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}");
        LOG.error("[direct/unevaluated extra c, ENABLED] errors: {}", errsBadEnabled);

        final int errsGoodEnabled = validateWithUnevaluated(schema, "{\"a\":\"x\",\"b\":\"y\"}");
        LOG.error("[direct/unevaluated only a,b, ENABLED] errors: {}", errsGoodEnabled);
    }

    @Test
    void const_without_type_is_handled_by_networknt() throws Exception {
        final String schema = "{"
                + "  \"$schema\": \"https://json-schema.org/draft/2020-12/schema\","
                + "  \"type\": \"object\","
                + "  \"properties\": {"
                + "    \"kind\": {\"const\": \"order.created\"}"
                + "  }"
                + "}";

        final int errsMatch = validate(schema, "{\"kind\":\"order.created\"}");
        LOG.error("[direct/const matching] errors: {}", errsMatch);

        final int errsMismatch = validate(schema, "{\"kind\":\"order.deleted\"}");
        LOG.error("[direct/const mismatch] errors: {}", errsMismatch);
    }

    @Test
    void boolean_schema_true_works_directly() throws Exception {
        final int errs = validate("true", "{\"anything\":[1,2,3]}");
        LOG.error("[direct/schema:true] errors: {}", errs);
    }

    @Test
    void boolean_schema_false_works_directly() throws Exception {
        final int errs = validate("false", "{\"anything\":[1,2,3]}");
        LOG.error("[direct/schema:false] errors: {}", errs);
    }

    @Test
    void discriminator_with_const_in_branch_works_directly() throws Exception {
        // Standard JSON Schema 2020-12 way to do discriminated unions
        final String schema = "{"
                + "  \"$schema\": \"https://json-schema.org/draft/2020-12/schema\","
                + "  \"oneOf\": ["
                + "    {\"type\": \"object\", \"required\": [\"kind\",\"radius\"], "
                + "     \"properties\": {\"kind\":{\"const\":\"circle\"},\"radius\":{\"type\":\"number\"}}},"
                + "    {\"type\": \"object\", \"required\": [\"kind\",\"side\"], "
                + "     \"properties\": {\"kind\":{\"const\":\"square\"},\"side\":{\"type\":\"number\"}}}"
                + "  ]"
                + "}";

        final int errsCircle = validate(schema, "{\"kind\":\"circle\",\"radius\":5}");
        LOG.error("[direct/oneOf+const circle] errors: {}", errsCircle);

        final int errsBad = validate(schema, "{\"kind\":\"circle\"}");
        LOG.error("[direct/oneOf+const circle missing radius] errors: {}", errsBad);
    }

    @Test
    void discriminator_with_mapping_via_oas31_metaschema() throws Exception {
        final String schema = "{"
                + "  \"oneOf\": ["
                + "    {\"$ref\":\"#/$defs/Circle\"},"
                + "    {\"$ref\":\"#/$defs/Square\"}"
                + "  ],"
                + "  \"discriminator\": {"
                + "    \"propertyName\": \"kind\","
                + "    \"mapping\": {"
                + "      \"circle\": \"#/$defs/Circle\","
                + "      \"square\": \"#/$defs/Square\""
                + "    }"
                + "  },"
                + "  \"$defs\": {"
                + "    \"Circle\": {\"type\":\"object\",\"required\":[\"kind\",\"radius\"],"
                + "      \"properties\":{\"kind\":{\"type\":\"string\"},\"radius\":{\"type\":\"number\"}}},"
                + "    \"Square\": {\"type\":\"object\",\"required\":[\"kind\",\"side\"],"
                + "      \"properties\":{\"kind\":{\"type\":\"string\"},\"side\":{\"type\":\"number\"}}}"
                + "  }"
                + "}";

        final var s = REGISTRY.getSchema(schema);
        final var errs = s.validate("{\"kind\":\"circle\",\"radius\":5}", InputFormat.JSON);
        errs.forEach(e -> LOG.error("    - {}", e));
        LOG.error("[direct/discriminator+mapping circle] errors: {}", errs.size());
    }
}
