package com.atlassian.oai.validator;

import com.atlassian.oai.validator.interaction.response.CustomResponseValidator;
import com.atlassian.oai.validator.model.ApiOperation;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolverFactory;
import com.atlassian.oai.validator.report.ValidationReport;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;

import static com.atlassian.oai.validator.model.Request.Method.GET;
import static com.atlassian.oai.validator.model.Request.Method.PATCH;
import static com.atlassian.oai.validator.util.ValidatorTestUtil.assertFail;
import static com.atlassian.oai.validator.util.ValidatorTestUtil.assertPass;
import static com.atlassian.oai.validator.util.ValidatorTestUtil.loadJsonResponse;
import static com.atlassian.oai.validator.util.ValidatorTestUtil.loadXmlResponse;
import static com.atlassian.oai.validator.util.ValidatorTestUtil.getContextsForKey;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.is;

public class OpenAPIV3ResponseValidationTest {

    private final OpenApiInteractionValidator classUnderTest =
            OpenApiInteractionValidator.createForSpecificationUrl("/oai/v3/api-users.yaml").build();

    @Test
    public void validate_withValidResponse_shouldSucceed() {
        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody(loadJsonResponse("users-valid"))
                .build();

        assertPass(classUnderTest.validateResponse("/users", GET, response));
    }

    @Test
    public void validate_withUnexpectedResponseBody_shouldFail() {
        final Response response = SimpleResponse.Builder
                .unauthorized()
                .withContentType("application/json")
                .withBody(loadJsonResponse("users-valid"))
                .build();

        assertFail(classUnderTest.validateResponse("/users", GET, response),
                "validation.response.body.unexpected");
    }

    @Test
    public void validate_withMissingResponseBody_shouldFail() {
        final Response response = SimpleResponse.Builder
                .ok()
                .build();

        assertFail(classUnderTest.validateResponse("/users", GET, response),
                "validation.response.body.missing");
    }

    @Test
    public void validate_withContentTypeButMissingResponseBody_shouldFail() {
        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .build();

        assertFail(classUnderTest.validateResponse("/users", GET, response),
                "validation.response.body.missing");
    }

    @Test
    public void validate_withUnsupportedContentType_shouldFail() {
        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("text/xml")
                .withBody(loadXmlResponse("users-valid"))
                .build();

        assertFail(classUnderTest.validateResponse("/users", GET, response),
                "validation.response.contentType.notAllowed");
    }

    @Test
    public void validate_withSupportedContentType_shouldPass_whenMultipleDefined() {
        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("text/xml")
                .withBody(loadXmlResponse("user-valid"))
                .build();

        assertPass(classUnderTest.validateResponse("/users/1", GET, response));
    }

    @Test
    public void validate_withResponseBodyMissingRequiredField_shouldFail() {
        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody(loadJsonResponse("user-invalid-missingrequired"))
                .build();

        assertFail(classUnderTest.validateResponse("/users/1", GET, response),
                "validation.response.body.schema.required");
    }

    @Test
    public void validate_withResponseBodyWithAdditionalFields_shouldFail() {
        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody(loadJsonResponse("user-invalid-additionalproperties"))
                .build();

        assertFail(classUnderTest.validateResponse("/users/1", GET, response),
                "validation.response.body.schema.additionalProperties");
    }

    @Test
    public void validate_withResponseContainingMalformedJson_shouldFail() {
        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody(loadJsonResponse("user-invalid-malformedjson"))
                .build();

        assertFail(classUnderTest.validateResponse("/users/1", GET, response),
                "validation.response.body.schema.invalidJson");
    }

    @Test
    public void validate_withCharsetInContentType_shouldPass_whenValid() {
        final OpenApiInteractionValidator classUnderTest =
                OpenApiInteractionValidator.createForSpecificationUrl("/oai/v3/api-with-complex-contenttypes.yaml").build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json; charset=utf-8")
                .withBody("1")
                .build();

        assertPass(classUnderTest.validateResponse("/response/charset/withoutwhitespace", GET, response));
    }

    @Test
    public void validate_withSubtypeWildcard_shouldPass_whenValid() {
        final OpenApiInteractionValidator classUnderTest =
                OpenApiInteractionValidator.createForSpecificationUrl("/oai/v3/api-with-complex-contenttypes.yaml").build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("image/gif")
                .withBody("123")
                .build();

        assertPass(classUnderTest.validateResponse("/response/wildcard/subtype", GET, response));
    }

    @Test
    public void validate_withSubtypeWildcard_shouldFail_whenInvalidContentType() {
        final OpenApiInteractionValidator classUnderTest =
                OpenApiInteractionValidator.createForSpecificationUrl("/oai/v3/api-with-complex-contenttypes.yaml").build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("123")
                .build();

        assertFail(classUnderTest.validateResponse("/response/wildcard/subtype", GET, response),
                "validation.response.contentType.notAllowed");
    }

    @Test
    public void validate_withResponseContainingUnknownStatusCode_shouldFail_whenNoDefaultResponseDefined() {
        final Response response = SimpleResponse.Builder.status(666).build();

        assertFail(classUnderTest.validateResponse("/users/1", GET, response),
                "validation.response.status.unknown");
    }

    @Test
    public void validate_withResponseContainingUnknownStatusCode_shouldPass_whenDefaultResponseDefined() {
        final Response response = SimpleResponse.Builder.status(666).withBody(loadJsonResponse("error-valid")).build();

        assertPass(classUnderTest.validateResponse("/users", GET, response));
    }

    @Test
    public void validate_withValidResponseHeader_shouldPass() {
        final Response response = SimpleResponse.Builder
                .serverError()
                .withHeader("X-Failure-Code", "123456")
                .build();

        assertPass(classUnderTest.validateResponse("/healthcheck", GET, response));
    }

    @Test
    public void validate_withInvalidResponseHeader_shouldFail() {
        final Response response = SimpleResponse.Builder
                .serverError()
                .withHeader("X-Failure-Code", "1.0")
                .build();

        final ValidationReport report = classUnderTest.validateResponse("/healthcheck", GET, response);

        assertFail(report, "validation.response.header.schema.type");
        getContextsForKey(report, "validation.response.header.schema.type").forEach(ctx -> {
                assertThat(ctx.isPresent(), is(true));
                assertThat(ctx.get().getHResponseHeaderName().isPresent(), is(true));
                assertThat(ctx.get().getHResponseHeaderName().get(), is("X-Failure-Code"));
            }
        );
    }

    @Test
    public void validate_withOneOfComposition_shouldPass_whenValid() {
        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-complex-composition.yaml")
                .withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored())
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("[{ \"stringField\": \"foo\" }, [{ \"intField\": 1 }, { \"boolField\": true }]]")
                .build();

        assertPass(classUnderTest.validateResponse("/oneOf", PATCH, response));
    }

    @Test
    public void validate_withOneOfComposition_shouldFail_whenInvalid() {
        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-complex-composition.yaml")
                .withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored())
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("[{ \"stringField\": \"foo\" }, [{ \"intField\": 1 }, { \"notAField\": true }]]")
                .build();

        assertFail(classUnderTest.validateResponse("/oneOf", PATCH, response),
                "validation.response.body.schema.oneOf");
    }

    @Test
    public void validate_withFormData_shouldPass_whenValid() {
        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-formdata.yaml")
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/x-www-form-urlencoded")
                .withBody("name=John%20Smith&email=john%40example.com&age=27")
                .build();

        assertPass(classUnderTest.validateResponse("/formdata", GET, response));
    }

    @Test
    public void validate_withFormData_shouldFail_whenInvalidSchema() {
        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-formdata.yaml")
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/x-www-form-urlencoded")
                .withBody("name=John%20Smith&email=john%40example.com&age=-27")
                .build();

        assertFail(classUnderTest.validateResponse("/formdata", GET, response), "validation.response.body.schema.minimum");
    }

    @Test
    public void validate_withCustomValidation_shouldPass() {
        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-users.yaml")
                .withCustomResponseValidation(new TestValidator())
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withHeader("Extension", "true")
                .build();

        assertPass(classUnderTest.validateResponse("/extensions", GET, response));
    }

    @Test
    public void validate_withCustomValidation_shouldFail() {
        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-users.yaml")
                .withCustomResponseValidation(new TestValidator())
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withHeader("Extension", "false")
                .build();

        assertFail(classUnderTest.validateResponse("/extensions", GET, response));
    }

    @Test
    public void validate_anyOfWithPrimitiveMatch_mustPass() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-anyof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"anyOfObjectProperty\": { \"primitive\": 1 } }")
                .build();

        final ValidationReport report = classUnderTest.validateResponse("/anyOfRequest", GET, response);
        assertPass(report);
    }

    @Test
    public void validate_anyOfWithPrimitiveUndefined_mustFail() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-anyof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"anyOfObjectProperty\": { \"primative\": false } }")
                .build();

        assertFail(classUnderTest.validateResponse("/anyOfRequest", GET, response));
    }

    @Test
    public void validate_anyOfWithObjectMatch_mustPass() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-anyof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"anyOfObjectProperty\": { \"objectModel\": { \"name\": \"Jack Sparrow\", \"email\" : \"capjacksparrow@pearl.com\" } } }")
                .build();

        assertPass(classUnderTest.validateResponse("/anyOfRequest", GET, response));
    }

    @Test
    public void validate_anyOfWithObjectUndefined_mustFail() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-anyof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"anyOfObjectProperty\": { \"objectModel\": { \"person\": \"Jack Sparrow\", \"contact\" : \"capjacksparrow@pearl.com\" } } }")
                .build();

        assertFail(classUnderTest.validateResponse("/anyOfRequest", GET, response));
    }

    @Test
    public void validate_anyOfWithArrayMatch_mustPass() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-anyof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"anyOfArrayProperty\": [ { \"name\": \"Jack Sparrow\", \"email\" : \"capjacksparrow@pearl.com\" },"
                        + " \"BALL\", 1, { \"foo\" : \"fooval\", \"bar\" : 2 } ] }")
                .build();

        assertPass(classUnderTest.validateResponse("/anyOfRequest", GET, response));
    }

    @Test
    public void validate_anyOfWithArrayUndefined_mustFail() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-anyof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"anyOfArrayProperty\": [ { \"name\": \"Jack Sparrow\", \"email\" : \"capjacksparrow@pearl.com\" },"
                        + " \"BALL\", 1, { \"foo\" : \"fooval\", \"bar\" : 2 }, true ] }")
                .build();

        assertFail(classUnderTest.validateResponse("/anyOfRequest", GET, response));
    }

    @Test
    public void validate_oneOfWithPrimitiveMatch_mustPass() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-oneof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"oneOfObjectProperty\": { \"primitive\": 1 } }")
                .build();

        final ValidationReport report = classUnderTest.validateResponse("/oneOfRequest", GET, response);
        assertPass(report);
    }

    @Test
    public void validate_oneOfWithPrimitiveUndefined_mustFail() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-oneof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"oneOfObjectProperty\": { \"primative\": false } }")
                .build();

        assertFail(classUnderTest.validateResponse("/oneOfRequest", GET, response));
    }

    @Test
    public void validate_oneOfWithObjectMatch_mustPass() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-oneof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"oneOfObjectProperty\": { \"objectModel\": { \"name\": \"Jack Sparrow\", \"email\" : \"capjacksparrow@pearl.com\" } } }")
                .build();

        assertPass(classUnderTest.validateResponse("/oneOfRequest", GET, response));
    }

    @Test
    public void validate_oneOfWithObjectUndefined_mustFail() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-oneof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"oneOfObjectProperty\": { \"objectModel\": { \"person\": \"Jack Sparrow\", \"contact\" : \"capjacksparrow@pearl.com\" } } }")
                .build();

        assertFail(classUnderTest.validateResponse("/oneOfRequest", GET, response));
    }

    @Test
    public void validate_oneOfWithArrayMatch_mustPass() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-oneof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"oneOfArrayProperty\": [ { \"name\": \"Jack Sparrow\", \"email\" : \"capjacksparrow@pearl.com\" },"
                        + " \"BALL\", 1, { \"foo\" : \"fooval\", \"bar\" : 2 } ] }")
                .build();

        assertPass(classUnderTest.validateResponse("/oneOfRequest", GET, response));
    }

    @Test
    public void validate_oneOfWithArrayUndefined_mustFail() {

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-oneof.yaml")
                .withResolveCombinators(true)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"oneOfArrayProperty\": [ { \"name\": \"Jack Sparrow\", \"email\" : \"capjacksparrow@pearl.com\" },"
                        + " \"BALL\", 1, { \"foo\" : \"fooval\", \"bar\" : 2 }, true ] }")
                .build();

        assertFail(classUnderTest.validateResponse("/oneOfRequest", GET, response));
    }

    @Test
    public void validate_deeplyNestedOneOf_mustPass() {

        final ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolveCombinators(true);
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);

        final OpenApiInteractionValidator classUnderTest = OpenApiInteractionValidator
                .createForSpecificationUrl("/oai/v3/api-oneof-complex.yaml")
                .withParseOptions(parseOptions)
                .build();

        final Response response = SimpleResponse.Builder
                .ok()
                .withContentType("application/json")
                .withBody("{ \"mods\" : { \"details\" : { \"container\" : [ { \"lineItems\" : [ { \"summary\" : [ { \"special\" : { \"form\" : { \"entryForm\" :"
                        + " { \"group\" : [ { \"key\" : \"number\", \"value\" : 1, \"valueType\" : \"NUMBER_UNSIGNED\" } ] }, \"selectionForm\" : { \"group\" : "
                        + "[ { \"key\" : \"sel\", \"value\" : \"1\", \"valueType\" : \"STRING\" } ] } } } } ], \"id\" : \"10\" } ], \"disabled\" : false } ] } }}")
                .build();

        final ValidationReport report = classUnderTest.validateResponse("/complex", Request.Method.POST, response);
        assertPass(report);
    }

    private class TestValidator implements CustomResponseValidator {
        @Override
        public ValidationReport validate(@Nonnull final Response response, @Nonnull final ApiOperation apiOperation) {
            final Optional<Object> extensionValue = apiOperation.getOperation().getExtensions().entrySet()
                    .stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("x-test-extension"))
                    .map(Map.Entry::getValue)
                    .findFirst();
            if (extensionValue.filter(value -> response.getHeaderValues("Extension").contains(value)).isPresent()) {
                return ValidationReport.empty();
            } else {
                return ValidationReport.singleton(ValidationReport.Message.create("test.extension", "Header extension didn't match expected value").build());
            }
        }
    }
}
