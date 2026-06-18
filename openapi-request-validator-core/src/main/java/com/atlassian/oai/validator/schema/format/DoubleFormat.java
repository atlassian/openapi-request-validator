package com.atlassian.oai.validator.schema.format;

import com.networknt.schema.ExecutionContext;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.format.Format;
import com.networknt.schema.utils.JsonType;
import com.networknt.schema.utils.TypeFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NumericNode;

import java.math.BigDecimal;

public class DoubleFormat implements Format {
    @Override
    public String getName() {
        return "double";
    }

    @Override
    public String getMessageKey() {
        return "format.double";
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
        final BigDecimal converted = BigDecimal.valueOf(numericValue.doubleValue());

        return dec.compareTo(converted) == 0;
    }
}
