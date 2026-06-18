package com.atlassian.oai.validator.v31analysis;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deep probe for the unevaluatedProperties failure mode. Tries every
 * combination of meta-schema and schema shape to find what makes it work.
 */
public class UnevaluatedDeepProbeTest {

    private static final Logger LOG = LoggerFactory.getLogger(UnevaluatedDeepProbeTest.class);

    private static int run(final String label,
                           final SchemaRegistry registry,
                           final String schemaJson,
                           final String input) throws Exception {
        final var schema = registry.getSchema(schemaJson);
        final var errs = schema.validate(input, InputFormat.JSON);
        LOG.error("[{}] errors: {}", label, errs.size());
        errs.forEach(e -> LOG.error("    - {}", e));
        return errs.size();
    }

    @Test
    void allOf_with_unevaluated_default_metaschema() throws Exception {
        final String schema = "{"
                + "\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                + "\"allOf\":["
                + "  {\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}},"
                + "  {\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"}}}"
                + "],"
                + "\"unevaluatedProperties\":false"
                + "}";
        final String bad = "{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}";

        run("default 2020-12", SchemaRegistry.withDefaultDialect(Dialects.getDraft202012()), schema, bad);
    }

    @Test
    void allOf_with_unevaluated_oas31_metaschema_NO_TYPE_SCHEMA_AT_TOP() throws Exception {
        // Reproduce what SchemaValidator does — uses OpenApi31 meta-schema as default
        final var registry = SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31());

        final String schema = "{"
                + "\"allOf\":["
                + "  {\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}},"
                + "  {\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"}}}"
                + "],"
                + "\"unevaluatedProperties\":false"
                + "}";
        final String bad = "{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}";

        run("OpenApi31 metaschema, no top type", registry, schema, bad);
    }

    @Test
    void allOf_with_additionalProperties_true_BREAKS_unevaluated() throws Exception {
        // Reproduces the SRV behaviour: when each allOf branch has
        // additionalProperties:true, networknt treats all properties as evaluated
        // and unevaluatedProperties:false has nothing left to flag.
        final String schema = "{"
                + "\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                + "\"allOf\":["
                + "  {\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"additionalProperties\":true},"
                + "  {\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"}},\"additionalProperties\":true}"
                + "],"
                + "\"unevaluatedProperties\":false"
                + "}";
        final String bad = "{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}";

        run("default 2020-12 with additionalProperties:true on branches", SchemaRegistry.withDefaultDialect(Dialects.getDraft202012()), schema, bad);
    }

    @Test
    void allOf_with_unevaluated_object_at_top_OAS31_metaschema() throws Exception {
        final var registry = SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31());

        final String schema = "{"
                + "\"type\":\"object\","
                + "\"allOf\":["
                + "  {\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}},"
                + "  {\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"}}}"
                + "],"
                + "\"unevaluatedProperties\":false"
                + "}";
        final String bad = "{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}";

        run("OpenApi31 metaschema, type:object at top", registry, schema, bad);
    }
}
