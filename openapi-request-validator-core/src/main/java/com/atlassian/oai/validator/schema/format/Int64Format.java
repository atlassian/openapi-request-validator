package com.atlassian.oai.validator.schema.format;

import com.networknt.schema.ExecutionContext;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.format.Format;
import com.networknt.schema.utils.JsonType;
import com.networknt.schema.utils.TypeFactory;
import tools.jackson.databind.JsonNode;

public class Int64Format implements Format {
    @Override
    public String getName() {
        return "int64";
    }

    @Override
    public String getMessageKey() {
        return "format.int64";
    }

    @Override
    public boolean matches(final ExecutionContext executionContext, final SchemaContext schemaContext, final JsonNode value) {
        final JsonType nodeType = TypeFactory.getValueNodeType(value, schemaContext.getSchemaRegistryConfig());
        return nodeType != JsonType.INTEGER || value.canConvertToLong();
    }
}
