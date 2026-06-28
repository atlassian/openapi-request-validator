package com.atlassian.oai.validator.report;

import com.atlassian.oai.validator.model.ApiOperation;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.whitelist.NamedWhitelistRule;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

/**
 * A report of validation errors that occurred during validation.
 * <p>
 * A report consists of a collection of messages with a given level.
 * Any message with a level of {@link Level#ERROR} indicates a validation failure.
 */
public interface ValidationReport {

    /**
     * The validation level
     */
    enum Level {
        ERROR,
        WARN,
        INFO,
        IGNORE
    }

    /**
     * A single message in the validation report
     */
    interface Message {

        static Builder create(final String key, final String message) {
            return new Builder(key, Level.ERROR, message);
        }

        String getKey();

        String getMessage();

        Level getLevel();

        List<String> getAdditionalInfo();

        default List<Message> getNestedMessages() {
            return Collections.emptyList();
        }

        /**
         * Returns contextual information about this message, if it is available.
         */
        Optional<MessageContext> getContext();

        /**
         * Returns a new instance, the same as this message, but, with level changed.
         */
        Message withLevel(Level level);

        /**
         * Returns a new instance, the same as this message, but with additional info attached.
         */
        Message withAdditionalInfo(String info);

        /**
         * Returns a new instance, the same as this message, but with nested messages attached.
         */
        default Message withNestedMessages(final Collection<Message> messages) {
            return this;
        }

        /**
         * Returns a new instance, the same as this message, but additional context attached.
         */
        Message withAdditionalContext(MessageContext context);

        class Builder {
            private final String key;
            private final ValidationReport.Level level;
            private final String message;
            private final List<String> additionalInfo = new ArrayList<>();
            private ValidationReport.MessageContext context;

            private Builder(@Nonnull final String key,
                            @Nonnull final ValidationReport.Level level,
                            @Nonnull final String message) {

                this.key = requireNonNull(key, "A key is required");
                this.level = requireNonNull(level, "A level is required");
                this.message = requireNonNull(message, "A message is required");
            }

            public Builder withAdditionalInfo(final List<String> additionalInfo) {
                if (additionalInfo != null) {
                    this.additionalInfo.addAll(additionalInfo);
                }
                return this;
            }

            public Builder withAdditionalInfo(final String... additionalInfo) {
                this.additionalInfo.addAll(asList(additionalInfo));
                return this;
            }

            public Builder withContext(final ValidationReport.MessageContext context) {
                this.context = context;
                return this;
            }

            public Message build() {
                return new ImmutableMessage(key, level, message, additionalInfo, context);
            }
        }

    }

    /**
     * Contextual information about a validation message.
     */
    interface MessageContext {

        enum Location {
            REQUEST,
            RESPONSE
        }

        /**
         * Pointers to the instance being validated and the schema being used for validation
         */
        class Pointers {
            private final String instance;
            private final String schema;

            public Pointers(final String instance, final String schema) {
                this.instance = instance;
                this.schema = schema;
            }

            public String getInstance() {
                return instance;
            }

            public String getSchema() {
                return schema;
            }
        }

        static MessageContext empty() {
            return create().build();
        }

        static Builder create() {
            return new Builder();
        }

        static Builder from(final MessageContext other) {
            return new Builder(other);
        }

        Optional<String> getRequestPath();

        Optional<Request.Method> getRequestMethod();

        Optional<ApiOperation> getApiOperation();

        Optional<String> getApiRequestContentType();

        Optional<RequestBody> getApiRequestBodyDefinition();

        Optional<Parameter> getParameter();

        Optional<Integer> getResponseStatus();

        Optional<String> getHResponseHeaderName();

        Optional<ApiResponse> getApiResponseDefinition();

        /**
         * @return Which part of the request/response interaction triggered the failure
         */
        Optional<Location> getLocation();

        /**
         * @return The whitelist rule applied to the message, if applicable.
         */
        Optional<NamedWhitelistRule> getAppliedWhitelistRule();

        /**
         * @return Pointers to the instance and schema that caused the validation failure,
         * if the failure is from schema validation.
         */
        Optional<Pointers> getPointers();

        /**
         * @return {@code true} if at least one field on this context object has been set; {@code false} otherwise.
         */
        boolean hasData();

        /**
         * Return a new MessageContext instance that contains all of the data in this context,
         * plus data from the incoming context where that data does not already exist on this context.
         * <p>
         * This is used to build a context up as more information becomes available.
         */
        MessageContext enhanceWith(MessageContext other);

        class Builder {
            String requestPath;
            Request.Method method;
            ApiOperation apiOperation;
            Parameter parameter;

            String apiRequestContentType;
            RequestBody apiRequestBodyDefinition;

            Integer responseStatus;
            String  responseHeaderName;
            ApiResponse apiResponse;

            Location location;

            NamedWhitelistRule whitelistRule;

            Pointers pointers;

            private Builder() {
            }

            private Builder(final MessageContext init) {
                requestPath = init.getRequestPath().orElse(null);
                method = init.getRequestMethod().orElse(null);
                apiOperation = init.getApiOperation().orElse(null);
                parameter = init.getParameter().orElse(null);
                apiRequestBodyDefinition = init.getApiRequestBodyDefinition().orElse(null);
                apiRequestContentType = init.getApiRequestContentType().orElse(null);
                responseStatus = init.getResponseStatus().orElse(null);
                responseHeaderName = init.getHResponseHeaderName().orElse(null);
                apiResponse = init.getApiResponseDefinition().orElse(null);
                location = init.getLocation().orElse(null);
                whitelistRule = init.getAppliedWhitelistRule().orElse(null);
                pointers = init.getPointers().orElse(null);
            }

            public Builder withRequestPath(final String requestPath) {
                this.requestPath = requestPath;
                return this;
            }

            public Builder withRequestMethod(final Request.Method method) {
                this.method = method;
                return this;
            }

            public Builder withApiOperation(final ApiOperation apiOperation) {
                this.apiOperation = apiOperation;
                return this;
            }

            public Builder withParameter(final Parameter parameter) {
                this.parameter = parameter;
                return this;
            }

            public Builder withApiRequestBodyDefinition(final RequestBody requestBody) {
                apiRequestBodyDefinition = requestBody;
                return this;
            }

            public Builder withMatchedApiContentType(final String contentType) {
                apiRequestContentType = contentType;
                return this;
            }

            public Builder withResponseStatus(final Integer status) {
                responseStatus = status;
                return this;
            }

            public Builder withResponseHeaderName(final String headerName) {
                responseHeaderName = headerName;
                return this;
            }

            public Builder withApiResponseDefinition(final ApiResponse apiResponseDefinition) {
                apiResponse = apiResponseDefinition;
                return this;
            }

            public Builder in(final Location location) {
                this.location = location;
                return this;
            }

            public Builder withAppliedWhitelistRule(final NamedWhitelistRule whitelistRule) {
                this.whitelistRule = whitelistRule;
                return this;
            }

            public Builder withPointers(final String instance, final String schema) {
                this.pointers = new Pointers(instance, schema);
                return this;
            }

            public Builder withAdditionalDataFrom(final MessageContext other) {
                if (requestPath == null) {
                    requestPath = other.getRequestPath().orElse(null);
                }
                if (method == null) {
                    method = other.getRequestMethod().orElse(null);
                }
                if (apiOperation == null) {
                    apiOperation = other.getApiOperation().orElse(null);
                }
                if (parameter == null) {
                    parameter = other.getParameter().orElse(null);
                }
                if (apiRequestContentType == null) {
                    apiRequestContentType = other.getApiRequestContentType().orElse(null);
                }
                if (apiRequestBodyDefinition == null) {
                    apiRequestBodyDefinition = other.getApiRequestBodyDefinition().orElse(null);
                }
                if (responseStatus == null) {
                    responseStatus = other.getResponseStatus().orElse(null);
                }
                if (responseHeaderName == null) {
                    responseHeaderName = other.getHResponseHeaderName().orElse(null);
                }
                if (apiResponse == null) {
                    apiResponse = other.getApiResponseDefinition().orElse(null);
                }
                if (location == null) {
                    location = other.getLocation().orElse(null);
                }
                if (whitelistRule == null) {
                    whitelistRule = other.getAppliedWhitelistRule().orElse(null);
                }
                if (pointers == null) {
                    pointers = other.getPointers().orElse(null);
                }
                return this;
            }

            public MessageContext build() {
                return new ImmutableMessageContext(this);
            }

        }
    }

    /**
     * Return an empty report.
     *
     * @return an immutable empty report
     */
    static ValidationReport empty() {
        return new EmptyValidationReport();
    }

    /**
     * Return an unmodifiable report that contains a single message.
     *
     * @param message The message to add to the report
     *
     * @return An unmodifiable validation report with a single message
     */
    static ValidationReport singleton(@Nullable final Message message) {
        if (message == null) {
            return empty();
        }
        return new ImmutableValidationReport(message);
    }

    /**
     * Return an unmodifiable report containing all the provided messages
     *
     * @param messages The messages to add to the report
     *
     * @return an unmodifiable report containing all the provided messages
     */
    static ValidationReport from(final Collection<Message> messages) {
        return from(messages.toArray(new Message[messages.size()]));
    }

    /**
     * Return an unmodifiable report containing all the provided messages
     *
     * @param messages The messages to add to the report
     *
     * @return an unmodifiable report containing all the provided messages
     */
    static ValidationReport from(final Message... messages) {
        if (messages == null || messages.length == 0) {
            return empty();
        }
        return new ImmutableValidationReport(messages);
    }

    /**
     * Return if this validation report contains errors.
     *
     * @return <code>true</code> if a validation error exists; <code>false</code> otherwise.
     */
    default boolean hasErrors() {
        return getMessages().stream().anyMatch(m -> m.getLevel() == Level.ERROR);
    }

    /**
     * Return sorted set of levels found during validation
     *
     * @return sorted set of levels, e.g. [ERROR, IGNORE]
     */
    default Set<Level> sortedValidationLevels() {
        return getMessages()
                .stream()
                .map(ValidationReport.Message::getLevel)
                .collect(toCollection(TreeSet::new));
    }

    /**
     * Get the validation messages on this report.
     *
     * @return The messages recorded on this report
     */
    @Nonnull
    List<Message> getMessages();

    /**
     * Merges the given validation report with this one, and return a new, unmodifiable report
     * containing the messages from both reports.
     *
     * @param other The validation report to merge with this one
     *
     * @return A new, unmodifiable validation report containing all the messages from this report
     * and the other report
     */
    default ValidationReport merge(@Nonnull final ValidationReport other) {
        requireNonNull(other, "A validation report is required");
        return new MergedValidationReport(this, other);
    }

    /**
     * Apply the given additional message context to each message in this validation report,
     * returning a new unmodifiable report.
     *
     * @param context The additional context to apply to each message in the report
     *
     * @return A new, unmodifiable validation report containing all of the messages from this report,
     * enhanced with the additional supplied context
     */
    ValidationReport withAdditionalContext(MessageContext context);
}
