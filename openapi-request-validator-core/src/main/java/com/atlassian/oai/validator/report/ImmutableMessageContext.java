package com.atlassian.oai.validator.report;

import com.atlassian.oai.validator.model.ApiOperation;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.whitelist.NamedWhitelistRule;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;

import java.util.Objects;
import java.util.Optional;

class ImmutableMessageContext implements ValidationReport.MessageContext {

    private final String requestPath;
    private final Request.Method method;
    private final ApiOperation apiOperation;
    private final Parameter parameter;

    private final RequestBody apiRequestBodyDefinition;
    private final String apiRequestContentType;

    private final Integer responseStatus;
    private final String responseHeaderName;
    private final ApiResponse apiResponseDefinition;

    private final Location location;

    private final NamedWhitelistRule whitelistRule;

    private final Pointers pointers;

    ImmutableMessageContext(final Builder builder) {
        requestPath = builder.requestPath;
        method = builder.method;
        apiOperation = builder.apiOperation;
        parameter = builder.parameter;
        apiRequestBodyDefinition = builder.apiRequestBodyDefinition;
        apiRequestContentType = builder.apiRequestContentType;
        responseStatus = builder.responseStatus;
        responseHeaderName = builder.responseHeaderName;
        apiResponseDefinition = builder.apiResponse;
        location = builder.location;
        whitelistRule = builder.whitelistRule;
        pointers = builder.pointers;
    }

    @Override
    public Optional<String> getRequestPath() {
        return Optional.ofNullable(requestPath);
    }

    @Override
    public Optional<Request.Method> getRequestMethod() {
        return Optional.ofNullable(method);
    }

    @JsonIgnore
    @Override
    public Optional<ApiOperation> getApiOperation() {
        return Optional.ofNullable(apiOperation);
    }

    @Override
    public Optional<Parameter> getParameter() {
        return Optional.ofNullable(parameter);
    }

    @JsonIgnore
    @Override
    public Optional<RequestBody> getApiRequestBodyDefinition() {
        return Optional.ofNullable(apiRequestBodyDefinition);
    }

    @Override
    public Optional<String> getApiRequestContentType() {
        return Optional.ofNullable(apiRequestContentType);
    }

    @Override
    public Optional<Integer> getResponseStatus() {
        return Optional.ofNullable(responseStatus);
    }

    @Override
    public Optional<String> getHResponseHeaderName() {
        return Optional.ofNullable(responseHeaderName);
    }

    @JsonIgnore
    @Override
    public Optional<ApiResponse> getApiResponseDefinition() {
        return Optional.ofNullable(apiResponseDefinition);
    }

    @Override
    public Optional<Location> getLocation() {
        return Optional.ofNullable(location);
    }

    @Override
    public Optional<NamedWhitelistRule> getAppliedWhitelistRule() {
        return Optional.ofNullable(whitelistRule);
    }

    @Override
    public Optional<Pointers> getPointers() {
        return Optional.ofNullable(pointers);
    }

    @Override
    public boolean hasData() {
        return requestPath != null ||
                method != null ||
                apiOperation != null ||
                apiRequestContentType != null ||
                apiRequestBodyDefinition != null ||
                parameter != null ||
                responseStatus != null ||
                apiResponseDefinition != null ||
                location != null ||
                whitelistRule != null ||
                pointers != null;
    }

    @Override
    public ValidationReport.MessageContext enhanceWith(final ValidationReport.MessageContext other) {
        return ValidationReport.MessageContext
                .from(this)
                .withAdditionalDataFrom(other)
                .build();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ImmutableMessageContext that = (ImmutableMessageContext) o;
        return Objects.equals(requestPath, that.requestPath) &&
                method == that.method &&
                Objects.equals(apiOperation, that.apiOperation) &&
                Objects.equals(parameter, that.parameter) &&
                Objects.equals(apiRequestBodyDefinition, that.apiRequestBodyDefinition) &&
                Objects.equals(apiRequestContentType, that.apiRequestContentType) &&
                Objects.equals(responseStatus, that.responseStatus) &&
                Objects.equals(apiResponseDefinition, that.apiResponseDefinition) &&
                Objects.equals(whitelistRule, that.whitelistRule) &&
                Objects.equals(pointers, that.pointers) &&
                location == that.location;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                requestPath, method, apiOperation,
                parameter, apiRequestBodyDefinition, apiRequestContentType,
                responseStatus, apiResponseDefinition, location, pointers,
                whitelistRule
        );
    }
}
