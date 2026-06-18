package com.atlassian.oai.validator.schema.format;

import com.networknt.schema.ExecutionContext;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.format.Format;
import com.networknt.schema.utils.JsonType;
import com.networknt.schema.utils.TypeFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NumericNode;

import java.math.BigDecimal;

public class FloatFormat implements Format {
    @Override
    public String getName() {
        return "float";
    }

    @Override
    public String getMessageKey() {
        return "format.float";
    }

    @Override
    public boolean matches(final ExecutionContext executionContext, final SchemaContext schemaContext, final JsonNode value) {
        final JsonType nodeType = TypeFactory.getValueNodeType(value, schemaContext.getSchemaRegistryConfig());

        if (nodeType != JsonType.NUMBER) {
            return true;
        }

        final NumericNode numericValue = (NumericNode) value;

        if (numericValue.isNaN()) {
            return false;
        }

        final BigDecimal dec = numericValue.decimalValue();
        // Derive the float from the decimal: BigDecimal#floatValue() yields Infinity on overflow,
        // whereas NumericNode#floatValue() throws in Jackson 3 when the value is out of float range.
        final float f = dec.floatValue();
        if (Float.isInfinite(f) || Float.isNaN(f)) {
            return false;
        }

        final String original = String.valueOf(dec);
        final String parsed = String.valueOf(f);

        return original.equals(parsed);
    }
}
