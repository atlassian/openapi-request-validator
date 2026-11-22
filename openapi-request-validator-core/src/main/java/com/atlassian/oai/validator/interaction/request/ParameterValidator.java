package com.atlassian.oai.validator.interaction.request;

import com.atlassian.oai.validator.report.MessageResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.schema.SchemaValidator;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static java.lang.Boolean.TRUE;
import static java.util.Objects.requireNonNull;

class ParameterValidator {

    private final SchemaValidator schemaValidator;
    private final MessageResolver messages;

    /**
     * Create a new validators object with the given schema validator.
     *
     * @param schemaValidator The schema validator to use.
     * @param messages The message resolver to use.
     */
    ParameterValidator(final SchemaValidator schemaValidator,
                       final MessageResolver messages) {
        this.schemaValidator = requireNonNull(schemaValidator);
        this.messages = requireNonNull(messages);
    }

    /**
     * Validate the given value against the given parameter.
     * <p>
     * If the parameter is an array type, the given value will be split according to the parameter style
     * and each sub-value validated independently.
     *
     * @param value The value to validate
     * @param parameter The parameter to validate against
     *
     * @return A report with any validation errors
     */
    @SuppressWarnings("checkstyle:UnnecessaryParentheses")
    ValidationReport validate(@Nullable final String value,
                              final Parameter parameter) {
        requireNonNull(parameter);

        final ValidationReport.MessageContext context =
                ValidationReport.MessageContext.create().withParameter(parameter).build();

        if (TRUE.equals(parameter.getRequired())) {
            // For required params, reject if null or empty and empty is disallowed
            if (value == null || (value.trim().isEmpty() && !emptyAllowed(parameter))) {
                return ValidationReport.singleton(
                        messages.get("validation.request.parameter.missing", parameter.getName())
                ).withAdditionalContext(context);
            }
        } else {
            // Optional null params should pass
            if (value == null) {
                return ValidationReport.empty();
            } else if (value.trim().isEmpty()) {
                // Optional empty params should pass if empty is allowed
                if (emptyAllowed(parameter)) {
                    return ValidationReport.empty();
                // If empty not allowed then String should proceed to schema validation (eg enum, pattern etc) but others fail
                } else if (!(parameter.getSchema() instanceof StringSchema)) {
                    return ValidationReport.singleton(
                            messages.get("validation.request.parameter.missing", parameter.getName())
                    ).withAdditionalContext(context);
                }
            }
        }

        if (isArraySchema(parameter.getSchema())) {
            return validateArrayParam(value, parameter).withAdditionalContext(context);
        }

        return schemaValidator.validate(value, parameter.getSchema(), "request.parameter")
                .withAdditionalContext(context);
    }

    /**
     * Validate the given values against the given parameter.
     * <p>
     * If multiple values are given, the parameter must be an array type and
     * it must have a style that supports multi-values (e.g. form + explode etc.).
     *
     * @param values The values to validate
     * @param parameter The parameter to validate against
     *
     * @return A report with any validation errors
     */
    ValidationReport validate(@Nullable final Collection<String> values,
                              final Parameter parameter) {
        final ValidationReport.MessageContext context =
                ValidationReport.MessageContext.create().withParameter(parameter).build();

        if (values == null) {
            if (TRUE.equals(parameter.getRequired())) {
                return ValidationReport.singleton(
                        messages.get("validation.request.parameter.missing", parameter.getName())
                ).withAdditionalContext(context);
            }
            return ValidationReport.empty();
        }

        if (!isArraySchema(parameter.getSchema())) {
            if (values.size() > 1) {
                return ValidationReport.singleton(
                        messages.get("validation.request.parameter.collection.invalid", parameter.getName())
                ).withAdditionalContext(context);
            }
            return schemaValidator.validate(values.iterator().next(), parameter.getSchema(), "request.parameter");
        }

        if (!ArraySeparator.from(parameter).isMultiValueParam()) {
            return ValidationReport.singleton(
                    messages.get("validation.request.parameter.collection.invalidFormat", parameter.getName(), parameter.getStyle(), false)
            ).withAdditionalContext(context);
        }

        return validateArrayParam(values, parameter).withAdditionalContext(context);
    }

    private ValidationReport validateArrayParam(final String value,
                                                final Parameter parameter) {
        return validateArrayParam(ArraySeparator.from(parameter).split(value), parameter);
    }

    private ValidationReport validateArrayParam(final Collection<String> values,
                                                final Parameter parameter) {
        final ValidationReport report = Stream.of(
                validateMaxItems(values, parameter),
                validateMinItems(values, parameter),
                validateUniqueItems(values, parameter)
        ).reduce(ValidationReport.empty(), ValidationReport::merge);

        if (parameter.getSchema().getEnum() != null && !parameter.getSchema().getEnum().isEmpty()) {
            final Set<String> enumValues = new HashSet<>(parameter.getSchema().getEnum());
            return values.stream()
                    .filter(v -> !enumValues.contains(v))
                    .map(v -> ValidationReport.singleton(messages.get("validation.request.parameter.enum.invalid",
                            v, parameter.getName(), parameter.getSchema().getEnum())
                    ))
                    .reduce(report, ValidationReport::merge);
        }

        return values.stream()
                .map(v -> schemaValidator.validate(
                        v, parameter.getSchema().getItems(), "request.parameter")
                )
                .reduce(report, ValidationReport::merge);
    }

    private ValidationReport validateUniqueItems(final Collection<String> values, final Parameter parameter) {
        if (TRUE.equals(parameter.getSchema().getUniqueItems()) &&
                values.stream().distinct().count() != values.size()) {
            return ValidationReport.singleton(messages.get("validation.request.parameter.collection.duplicateItems",
                    parameter.getName())
            );
        }
        return ValidationReport.empty();
    }

    private ValidationReport validateMinItems(final Collection<String> values, final Parameter parameter) {
        if (parameter.getSchema().getMinItems() != null && values.size() < parameter.getSchema().getMinItems()) {
            return ValidationReport.singleton(messages.get("validation.request.parameter.collection.tooFewItems",
                    parameter.getName(), parameter.getSchema().getMinItems(), values.size())
            );
        }
        return ValidationReport.empty();
    }

    private ValidationReport validateMaxItems(final Collection<String> values, final Parameter parameter) {
        if (parameter.getSchema().getMaxItems() != null && values.size() > parameter.getSchema().getMaxItems()) {
            return ValidationReport.singleton(messages.get("validation.request.parameter.collection.tooManyItems",
                    parameter.getName(), parameter.getSchema().getMaxItems(), values.size())
            );
        }
        return ValidationReport.empty();
    }

    /**
     * Determine whether the given schema describes an array.
     * <p>
     * OpenAPI 3.0 array schemas are parsed as {@link ArraySchema}. OpenAPI 3.1 schemas are parsed as generic schemas
     * where the type is held in {@link Schema#getTypes()} (e.g. {@code [array]} or {@code [array, null]}), and
     * {@link Schema#getType()} may not be populated.
     *
     * @param schema The schema to inspect (may be {@code null}, e.g. for parameters defined with {@code content})
     *
     * @return {@code true} if the schema describes an array
     */
    private static boolean isArraySchema(@Nullable final Schema<?> schema) {
        if (schema == null) {
            return false;
        }
        if (schema instanceof ArraySchema || "array".equals(schema.getType())) {
            return true;
        }
        final Set<?> types = schema.getTypes();
        if (types == null || !types.contains("array")) {
            return false;
        }
        // Only treat as an array if "array" is the sole non-null type; other unions (e.g. [array, string]) are ambiguous
        return types.stream().allMatch(t -> "array".equals(t) || "null".equals(t));
    }

    @SuppressWarnings("checkstyle:UnnecessaryParentheses")
    private boolean emptyAllowed(final Parameter parameter) {
        // See https://swagger.io/specification/#parameter-object
        return (TRUE.equals(parameter.getAllowEmptyValue())
                && "query".equalsIgnoreCase(parameter.getIn())
                // It's unclear from the spec whether this should be restricted to String schemas,
                // but it doesn't make sense not to IMHO
                && parameter.getSchema() instanceof StringSchema)
                || isArraySchema(parameter.getSchema());
    }

}