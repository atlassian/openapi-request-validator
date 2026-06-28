package com.atlassian.oai.validator.interaction.response;

import com.atlassian.oai.validator.model.ApiOperation;
import com.atlassian.oai.validator.model.Body;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.report.MessageResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.report.ValidationReport.MessageContext;
import com.atlassian.oai.validator.schema.SchemaValidator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.atlassian.oai.validator.report.ValidationReport.MessageContext.Location.RESPONSE;
import static com.atlassian.oai.validator.report.ValidationReport.empty;
import static com.atlassian.oai.validator.util.ContentTypeUtils.containsGlobalAccept;
import static com.atlassian.oai.validator.util.ContentTypeUtils.findMostSpecificMatch;
import static com.atlassian.oai.validator.util.ContentTypeUtils.isFormDataContentType;
import static com.atlassian.oai.validator.util.ContentTypeUtils.isJsonContentType;
import static com.atlassian.oai.validator.util.ContentTypeUtils.matchesAny;
import static com.atlassian.oai.validator.util.HttpParsingUtils.parseUrlEncodedFormDataBodyAsJsonNode;
import static java.lang.Boolean.TRUE;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Validate a response against an API operation
 */
public class ResponseValidator {

    private static final Logger log = getLogger(ResponseValidator.class);

    private final SchemaValidator schemaValidator;
    private final MessageResolver messages;
    private final OpenAPI api;
    private final List<CustomResponseValidator> customResponseValidators;

    /**
     * Construct a new response validator with the given schema validator.
     *
     * @param schemaValidator The schema validator to use when validating response bodies
     * @param messages The message resolver to use
     * @param api The OpenAPI spec to validate against
     * @param customResponseValidators The list of custom validators to run
     */
    public ResponseValidator(final SchemaValidator schemaValidator,
                             final MessageResolver messages,
                             final OpenAPI api,
                             final List<CustomResponseValidator> customResponseValidators) {
        this.schemaValidator = requireNonNull(schemaValidator, "A schema validator is required");
        this.messages = requireNonNull(messages, "A message resolver is required");
        this.api = requireNonNull(api, "An OAI definition is required");
        this.customResponseValidators = customResponseValidators;
    }

    /**
     * Validate the given response against the API operation.
     *
     * @param response The response to validate
     * @param apiOperation The API operation to validate the response against
     *
     * @return A validation report containing validation errors
     */
    @Nonnull
    public ValidationReport validateResponse(final Response response, final ApiOperation apiOperation) {
        requireNonNull(response, "A response is required");
        requireNonNull(apiOperation, "An API operation is required");

        final ApiResponse apiResponse = getApiResponse(response, apiOperation);

        final MessageContext.Builder contextBuilder = MessageContext.create()
                .in(RESPONSE)
                .withApiOperation(apiOperation);

        if (apiResponse == null) {
            return ValidationReport.singleton(
                    messages.get("validation.response.status.unknown",
                            response.getStatus(), apiOperation.getApiPath().original())
            ).withAdditionalContext(contextBuilder.build());
        }

        return validateResponseBody(response, apiResponse, apiOperation)
                .merge(validateContentType(response, apiOperation))
                .merge(validateHeaders(response, apiResponse, apiOperation))
                .merge(validateCustom(response, apiOperation))
                .withAdditionalContext(
                        contextBuilder
                                .withResponseStatus(response.getStatus())
                                .withApiResponseDefinition(apiResponse)
                                .build()
                );
    }

    @Nullable
    private ApiResponse getApiResponse(final Response response,
                                       final ApiOperation apiOperation) {
        final ApiResponses responses = apiOperation.getOperation().getResponses();
        final ApiResponse apiResponse = responses.get(Integer.toString(response.getStatus()));
        if (apiResponse != null) {
            return apiResponse;
        }
        final ApiResponse apiRangeResponse = responses.get(Integer.toString(response.getStatus() / 100) + "XX");
        if (apiRangeResponse != null) {
            return apiRangeResponse;
        }

        return responses.get("default"); // try the default response
    }

    @Nonnull
    private ValidationReport validateResponseBody(final Response response,
                                                  final ApiResponse apiResponse,
                                                  final ApiOperation apiOperation) {
        final Optional<Body> responseBody = response.getResponseBody();
        final boolean hasBody = responseBody.map(Body::hasBody).orElse(false);
        // Content field is null in OpenAPI v3 and initialized but empty in Swagger v2 when no response body is defined
        final boolean noBodyDefinedInSpecification = apiResponse.getContent() == null || apiResponse.getContent().isEmpty();

        if (noBodyDefinedInSpecification && hasBody) {
            // A response body exists, but no response body is defined in the spec
            return ValidationReport.singleton(messages.get("validation.response.body.unexpected"));
        }

        if (noBodyDefinedInSpecification) {
            // No response body is defined in the spec and none was provided -> Nothing to do
            return ValidationReport.empty();
        }

        if (!hasBody) {
            // No response body exists, but a response body was defined in the spec
            return ValidationReport.singleton(
                    messages.get("validation.response.body.missing",
                            apiOperation.getMethod(), apiOperation.getApiPath().original()));
        }

        final Optional<String> mostSpecificMatch = findMostSpecificMatch(response, apiResponse.getContent().keySet());

        if (!mostSpecificMatch.isPresent()) {
            // Validation of invalid content type is handled in content type validation
            return ValidationReport.empty();
        }

        final MediaType apiMediaType = apiResponse.getContent().get(mostSpecificMatch.get());
        if (apiMediaType.getSchema() == null) {
            return ValidationReport.empty();
        }

        if (isJsonContentType(response)) {
            return schemaValidator
                    .validate(() -> responseBody.get().toJsonNode(),
                            apiMediaType.getSchema(), "response.body");
        }

        if (isFormDataContentType(response)) {
            return schemaValidator
                    .validate(() -> parseUrlEncodedFormDataBodyAsJsonNode(responseBody.get().toString(StandardCharsets.UTF_8)),
                            apiMediaType.getSchema(), "response.body");
        }

        if (response.getContentType().isPresent()) {
            log.info("Validation of '{}' not supported. Response body not validated.", response.getContentType().get());
        }

        return empty();
    }

    @Nonnull
    private ValidationReport validateContentType(final Response response,
                                                 final ApiOperation apiOperation) {

        final Optional<String> responseContentTypeHeader = response.getContentType();
        if (!responseContentTypeHeader.isPresent()) {
            return ValidationReport.empty();
        }

        final com.atlassian.oai.validator.util.MediaType responseMediaType;
        try {
            responseMediaType = com.atlassian.oai.validator.util.MediaType.parse(responseContentTypeHeader.get());
        } catch (final IllegalArgumentException e) {
            return ValidationReport.singleton(messages.get(
                    "validation.response.contentType.invalid", responseContentTypeHeader.get())
            );
        }

        final Collection<String> apiMediaTypes = getApiMediaTypesForResponse(response, apiOperation);
        if (apiMediaTypes.isEmpty() || containsGlobalAccept(apiMediaTypes)) {
            return empty();
        }

        if (!matchesAny(responseMediaType, apiMediaTypes)) {
            return ValidationReport.singleton(
                    messages.get("validation.response.contentType.notAllowed", responseContentTypeHeader.get(), apiMediaTypes)
            );
        }

        return ValidationReport.empty();
    }

    @Nonnull
    private Collection<String> getApiMediaTypesForResponse(final Response response,
                                                           final ApiOperation apiOperation) {
        final ApiResponse apiResponse = getApiResponse(response, apiOperation);
        if (apiResponse == null) {
            return emptyList();
        }
        return defaultIfNull(apiResponse.getContent(), new Content()).keySet();
    }

    @Nonnull
    private ValidationReport validateHeaders(final Response response,
                                             final ApiResponse apiResponse,
                                             final ApiOperation apiOperation) {

        final Map<String, Header> apiHeaders = apiResponse.getHeaders();
        if (apiHeaders == null || apiHeaders.isEmpty()) {
            return ValidationReport.empty();
        }

        return apiHeaders.entrySet()
                .stream()
                .map(h -> validateHeader(apiOperation, h.getKey(), h.getValue(), response.getHeaderValues(h.getKey())))
                .reduce(ValidationReport.empty(), ValidationReport::merge);

    }

    @Nonnull
    private ValidationReport validateHeader(final ApiOperation apiOperation,
                                            final String headerName,
                                            final Header apiHeader,
                                            final Collection<String> propertyValues) {
        final ValidationReport.MessageContext context =
                ValidationReport.MessageContext.create().withResponseHeaderName(headerName).build();

        if (propertyValues.isEmpty() && TRUE.equals(apiHeader.getRequired())) {
            return ValidationReport.singleton(
                    messages.get("validation.response.header.missing", headerName, apiOperation.getApiPath().original())
            ).withAdditionalContext(context);
        }

        return propertyValues
                .stream()
                .map(v -> schemaValidator.validate(v, apiHeader.getSchema(), "response.header")
                        .withAdditionalContext(context))
                .reduce(ValidationReport.empty(), ValidationReport::merge);
    }

    @Nonnull
    private ValidationReport validateCustom(final Response response,
                                            final ApiOperation apiOperation) {
        return customResponseValidators
                .stream()
                .map(customResponseValidator -> customResponseValidator.validate(response, apiOperation))
                .reduce(ValidationReport.empty(), ValidationReport::merge);
    }
}
