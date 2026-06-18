package com.atlassian.oai.validator.schema;

import com.atlassian.oai.validator.report.MessageResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.schema.format.Base64Format;
import com.atlassian.oai.validator.schema.format.DoubleFormat;
import com.atlassian.oai.validator.schema.format.FloatFormat;
import com.atlassian.oai.validator.schema.format.Int32Format;
import com.atlassian.oai.validator.schema.format.Int64Format;
import com.atlassian.oai.validator.schema.transform.AdditionalPropertiesInjectionTransformer;
import com.atlassian.oai.validator.schema.transform.DiscriminatorMappingTransformer;
import com.atlassian.oai.validator.schema.transform.RequiredFieldTransformer;
import com.atlassian.oai.validator.schema.transform.SchemaDefinitionsInjectionTransformer;
import com.atlassian.oai.validator.schema.transform.SchemaTransformationContext;
import com.atlassian.oai.validator.schema.transform.SchemaTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.InvalidSchemaException;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.DialectRegistry;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.dialect.OpenApi30;
import com.networknt.schema.dialect.OpenApi31;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    public static final String ADDITIONAL_PROPERTIES_KEY = "validation.schema.additionalProperties";

    public static final String INVALID_JSON_KEY = "validation.schema.invalidJson";

    private final MessageResolver messages;
    private final LoadingCache<JsonSchemaKey, com.networknt.schema.Schema> jsonSchemaCache;
    private final SchemaRegistry schemaRegistry;
    private final JsonNode definitions;
    private final ValidationConfiguration validationConfiguration;
    private final ValidationMessageConverter messageConverter;

    /**
     * Flag indicating whether the validator is operating in OpenAPI 3.0 mode.
     * <p>
     * This value is derived from the {@link OpenAPI#getSpecVersion()} of the provided API
     * definition.
     * <ul>
     * <li>If {@code true}, the validator configures the underlying {@link DialectRegistry} to use
     * {@link SpecificationVersion#DRAFT_4} (JSON Schema Draft 4) and the {@link OpenApi30} meta-schema.
     * It also utilizes the standard {@link Json#mapper()} for JSON processing.</li>
     * <li>If {@code false}, it assumes OpenAPI 3.1 context, configuring the factory for
     * {@link SpecificationVersion#DRAFT_2020_12} (JSON Schema 2020-12) and the {@link OpenApi31} meta-schema.
     * In this mode, {@link Json31#mapper()} is used to support 3.1 specific features.</li>
     * </ul>
     */
    private final boolean isOpenApi30;

    /**
     * Transformations applied to the schema before validation.
     * <p>
     * Order is important here - the mutations from one transformation are passed through to the subsequent transformers.
     */
    private final List<SchemaTransformer> transformers;

    /**
     * Build a new validator for the given API specification.
     *
     * @param api The API to build the validator for.
     * @param messages The message resolver to use.
     */
    public SchemaValidator(final OpenAPI api,
        @Nonnull final MessageResolver messages) {
        this(api, messages, new ValidationConfiguration());
    }

    public SchemaValidator(@Nonnull final OpenAPI api,
                                  @Nonnull final MessageResolver messages,
                                  @Nonnull final ValidationConfiguration validationConfiguration) {
        this.messages = requireNonNull(messages);
        this.validationConfiguration = requireNonNull(validationConfiguration);
        this.messageConverter = new ValidationMessageConverter(messages);

        this.isOpenApi30 = api.getSpecVersion() == SpecVersion.V30;

        final Dialect defaultDialect = isOpenApi30 ? Dialects.getOpenApi30() : Dialects.getOpenApi31();
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(dialectWithFormats(defaultDialect), builder -> {
            final SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                    .formatAssertionsEnabled(true)
                    .build();

            builder.schemaRegistryConfig(config);
        });

        this.definitions = Optional.ofNullable(api.getComponents())
                .map(Components::getSchemas)
            .map(schemas -> isOpenApi30 ? Json.mapper().convertValue(schemas, JsonNode.class)
                : Json31.mapper().convertValue(schemas, JsonNode.class))
            .orElseGet(() -> isOpenApi30 ? Json.mapper().createObjectNode()
                : Json31.mapper().createObjectNode());

        final List<SchemaTransformer> transformers = Arrays.asList(
            SchemaDefinitionsInjectionTransformer.getInstance(),
            DiscriminatorMappingTransformer.getInstance(),
            AdditionalPropertiesInjectionTransformer.getInstance(),
            RequiredFieldTransformer.getInstance());
        this.transformers = transformers;

        if (validationConfiguration.isCacheEnabled()) {
            this.jsonSchemaCache = Caffeine.newBuilder()
                    .maximumSize(validationConfiguration.getMaxCacheSize())
                    .build(key -> {
                        final JsonNode schemaObject = readAndTransformSchemaObject(
                                key.schema,
                                key.forRequest,
                                key.forResponse,
                                definitions
                        );
                        return schemaRegistry.getSchema(writeAsString(schemaObject), InputFormat.JSON);
                    });
        } else {
            this.jsonSchemaCache = null;
        }
    }

    private Dialect dialectWithFormats(final Dialect base) {
        return Dialect.builder(base)
            .format(new Int64Format())
            .format(new Int32Format())
            .format(new DoubleFormat())
            .format(new FloatFormat())
            .format(new Base64Format())
            .build();
    }

    /**
     * Checks whether the given schema defines multiple types without specifying a single type.
     * This is used to determine if the schema should be validated as a multi-type schema.
     *
     * @param schema The schema to inspect
     * @return {@code true} if the schema defines multiple types and does not specify a single type, {@code false} otherwise
     */
    private boolean hasMultipartTypeSchema(@Nullable final Schema schema) {
        return schema != null
            && schema.getType() == null
            && schema.getTypes() != null
            && schema.getTypes().size() > 0;
    }

    /**
     * Validates the given value against a schema that allows multiple possible types.
     * Iterates through each type defined in the schema and returns the result of the first successful validation.
     * If none of the types validate successfully, returns the validation report with errors from the last attempted type.
     *
     * @param value     The value to validate
     * @param schema    The schema that defines multiple possible types
     * @param keyPrefix A prefix to apply to validation messages emitted by the validator
     * @return A validation report indicating success or detailing validation errors
     * @throws NullPointerException if the schema is null
     */
    @Nonnull
    public ValidationReport validateMultiTypeSchema(@Nonnull final String value,
        @Nonnull final Schema schema,
        @Nullable final String keyPrefix) {
        log.debug("Validating multi value type schema");
        requireNonNull(schema, "A schema is required");
        ValidationReport finalReport = ValidationReport.empty();
        for (Object type : schema.getTypes()) {
            final ValidationReport report = validate(() -> readContent(value, schema, (String) type),
                schema, keyPrefix);
            if (!report.hasErrors()) {
                return report; // found 1 matching and valid type in the schema
            } else {
                finalReport = report;
            }
        }
        return finalReport;
    }

    /**
     * Validate the given value against the given property schema. If the schema is null then any json is valid.
     * If the schema is multipart type, check against all types.
     *
     * @param value     The value to validate
     * @param schema    The schema to validate the value against
     * @param keyPrefix A prefix to apply to validation messages emitted by the validator
     * @return A validation report containing accumulated validation errors
     */
    @Nonnull
    public ValidationReport validate(@Nonnull final String value,
        @Nullable final Schema schema,
        @Nullable final String keyPrefix) {
        requireNonNull(value, "A value is required");
        if (hasMultipartTypeSchema(schema)) {
            return validateMultiTypeSchema(value, schema, keyPrefix);
        }
        final String type = (schema == null) ? null : schema.getType();
        return validate(() -> readContent(value, schema, type), schema, keyPrefix);
    }

    @FunctionalInterface
    public interface JsonNodeSupplier {
        JsonNode get() throws IOException;
    }

    @Nonnull
    public ValidationReport validate(@Nonnull final JsonNodeSupplier supplier,
                                     @Nullable final Schema<?> schema,
                                     @Nullable final String keyPrefix) {
        if (schema == null) {
            return ValidationReport.empty();
        }

        try {
            final com.networknt.schema.Schema resolvedJsonSchema = resolveJsonSchema(schema, keyPrefix);
            final List<Error> validationMessages =
                    resolvedJsonSchema.validate(writeAsString(supplier.get()), InputFormat.JSON);
            return messageConverter.toValidationReport(validationMessages, keyPrefix);
        } catch (final InvalidSchemaException e) {
            return ValidationReport.singleton(
                    messages.create("validation." + keyPrefix + ".schema.processingError", e.getMessage())
            );
        } catch (final IOException e) {
            return ValidationReport.singleton(
                    messages.create(
                            "validation." + keyPrefix + ".schema.invalidJson",
                            messages.get(INVALID_JSON_KEY, e.getMessage()).getMessage()
                    )
            );
        }
    }

    private com.networknt.schema.Schema resolveJsonSchema(final Schema<?> schema,
                                                          @Nullable final String keyPrefix) {
        final boolean forRequest = "request.body".equalsIgnoreCase(keyPrefix);
        final boolean forResponse = "response.body".equalsIgnoreCase(keyPrefix);
        final JsonSchemaKey jsonSchemaKey = new JsonSchemaKey(schema, forRequest, forResponse);
        try {
            if (validationConfiguration.isCacheEnabled()) {
                return jsonSchemaCache.get(jsonSchemaKey);
            }
            final JsonNode schemaObject = readAndTransformSchemaObject(schema, forRequest, forResponse, definitions);
            return schemaRegistry.getSchema(writeAsString(schemaObject), InputFormat.JSON);
        } catch (final Exception e) {
            // TODO: Need to handle this exception
            throw new RuntimeException("JsonSchema construction failed", e);
        }
    }

    private JsonNode readAndTransformSchemaObject(final Schema<?> schema,
                                                  final boolean forRequest,
                                                  final boolean forResponse,
                                                  final JsonNode definitions) {
        final ObjectNode schemaObject =
            isOpenApi30 ? Json.mapper().convertValue(schema, ObjectNode.class)
                : Json31.mapper().convertValue(schema, ObjectNode.class);
        final SchemaTransformationContext transformationContext = SchemaTransformationContext.create()
                .forRequest(forRequest)
                .forResponse(forResponse)
                .withAdditionalPropertiesValidation(additionalPropertiesValidationEnabled())
                // Use a copy of the definitions. The JsonSchema validation process might change them
                // in its validation process. On concurrent validations it might even lead to
                // ConcurrentModificationException.
                .withDefinitions(definitions.deepCopy())
                .withIsOpenApi30(isOpenApi30)
                .build();

        transformers.forEach(t -> t.apply(schemaObject, transformationContext));
        return schemaObject;
    }

    /**
     * Serialize a (Jackson 2) JSON node to a JSON string.
     * <p>
     * swagger-core / swagger-parser produce Jackson 2 nodes, whereas networknt 3.x consumes Jackson 3.
     * Rather than leak Jackson 2 nodes into networknt (which removed the Jackson 2 node overloads),
     * the schema and the instance being validated are handed to networknt as JSON text via its
     * {@link InputFormat#JSON} entry points.
     */
    private String writeAsString(final JsonNode node) {
        try {
            return (isOpenApi30 ? Json.mapper() : Json31.mapper()).writeValueAsString(node);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to serialize JSON node", e);
        }
    }

    private boolean additionalPropertiesValidationEnabled() {
        return !messages.isIgnored(ADDITIONAL_PROPERTIES_KEY);
    }

    private JsonNode readContent(@Nonnull final String value,
        @Nullable final Schema schema,
        @Nullable final String type) throws IOException {
        if ("string".equalsIgnoreCase(type)) {
            return createStringNode(value);
        }
        if ("null".equalsIgnoreCase(value)) {
            return isOpenApi30 ? Json.mapper().readTree("null") : Json31.mapper().readTree("null");
        }
        if (schema instanceof DateTimeSchema) {
            return createStringNode(normaliseDateTime(value));
        }
        if ("number".equalsIgnoreCase(type) ||
            "integer".equalsIgnoreCase(type)) {
            return createNumericNode(value);
        }
        // If not all refs were resolved (ie resolveFully is set to false) then try and resolve it once
        final Optional<String> referenceType = type == null ? resolveReferenceType(schema) : Optional.empty();
        if (referenceType.isPresent()) {
            // Only try to resolve the ref once, as to avoid stack overflow with recursive schemas
            return readContent(value, null, referenceType.get());
        }
        return isOpenApi30 ? Json.mapper().readTree(value) : Json31.mapper().readTree(value);
    }

    private Optional<String> resolveReferenceType(@Nullable final Schema schema) {
        if (schema != null && schema.get$ref() != null) {
            final String definitionName = schema.get$ref().replace("#/components/schemas/", "").replace("#/definitions/", "");
            final JsonNode refSchema = definitions.get(definitionName);
            if (refSchema != null) {
                return Optional.ofNullable(refSchema.get("type")).map(JsonNode::asText);
            }
        }
        return Optional.empty();
    }

    private static JsonNode createStringNode(final String value) {
        return new TextNode(value);
    }

    private static JsonNode createNumericNode(final String value) throws IOException {
        try {
            Double.parseDouble(value);
            // Valid number. Leave unquoted.
            return Json.mapper().readTree(value);
        } catch (final NumberFormatException e) {
            // Invalid number. Schema validator will generate appropriate errors.
            return createStringNode(value);
        }
    }

    private static String normaliseDateTime(final String dateTime) {
        // Re-format DateTime since Schema validator doesn't accept some valid RFC3339 date-times and throws:
        // ERROR - String "1996-12-19T16:39:57-08:00" is invalid against requested date format(s)
        // [yyyy-MM-dd'T'HH:mm:ssZ, yyyy-MM-dd'T'HH:mm:ss.[0-9]{1,12}Z]: []
        try {
            final LocalDateTime rfc3339dt = LocalDateTime.parse(dateTime, CustomDateTimeFormatter.getRFC3339Formatter());
            return rfc3339dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        } catch (final DateTimeParseException e) {
            // Could not parse to RFC3339 format. Schema validator will throw the appropriate error
            return dateTime;
        }
    }

    private static class JsonSchemaKey {
        private final Schema schema;
        private final boolean forRequest;
        private final boolean forResponse;

        private JsonSchemaKey(final Schema schema, final boolean forRequest, final boolean forResponse) {
            this.schema = schema;
            this.forRequest = forRequest;
            this.forResponse = forResponse;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final JsonSchemaKey that = (JsonSchemaKey) o;
            return forRequest == that.forRequest && forResponse == that.forResponse
                    && Objects.equals(schema, that.schema);
        }

        @Override
        public int hashCode() {
            return Objects.hash(forRequest, forResponse, schema);
        }
    }
}