package com.atlassian.oai.validator.schema;

import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.MessageResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.LoadingCache;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.atlassian.oai.validator.schema.SchemaValidator.ADDITIONAL_PROPERTIES_KEY;
import static com.atlassian.oai.validator.util.ValidatorTestUtil.assertFailWithoutContext;
import static com.atlassian.oai.validator.util.ValidatorTestUtil.assertPass;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SchemaValidatorTest {

    private final SchemaValidator classUnderTest = validator("/oai/v2/api-users.json");

    @Test
    public void validate_withNullValue_shouldThrowException() {
        final String value = null;
        final Schema schema = new Schema();

        assertThrows(NullPointerException.class, () -> classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withStringNull_shouldPass() {
        final String value = "null";
        final Schema schema = new Schema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withEmptyValue_shouldPass_whenMinLengthZero() {
        final String value = "";
        final Schema schema = new StringSchema().minLength(0);

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withEmptyValue_shouldFail_whenMinLengthNonZero() {
        final String value = "";
        final Schema schema = new StringSchema().minLength(1);

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.minLength");
    }

    @Test
    public void validate_withNullSchema_shouldValidateAnyJson() {
        final List<String> values = List.of("1", "\"string\"", "{\"prop\":3}", "[1,2,3]", "null");

        values.forEach(v -> assertPass(classUnderTest.validate(v, null, "prefix")));
    }

    @Test
    public void validate_withValidProperty_shouldPass() {
        final String value = "1";
        final Schema schema = new StringSchema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withInvalidProperty_shouldFail() {
        final String value = "1.0";
        final Schema schema = new IntegerSchema();

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.type");
    }

    @Test
    public void validate_withUnquotedStringProperty_shouldPass() {
        final String value = "bob";
        final Schema schema = new StringSchema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withQuotedStringProperty_shouldPass() {
        final String value = "\"bob\"";
        final Schema schema = new StringSchema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withBackslash_shouldPass() {
        final String value = "foo\\car";
        final Schema schema = new StringSchema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withInvalidInt32Format_shouldFail() {
        final String value = Integer.MAX_VALUE + "0";
        final Schema schema = new IntegerSchema().format("int32");

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.format.int32");
    }

    @Test
    public void validate_withEmptyInt32Format_shouldFail() {
        final String value = "";
        final Schema schema = new IntegerSchema().format("int32");

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.type");
    }

    @Test
    public void validate_withInvalidInt64Format_shouldFail() {
        final String value = Long.MAX_VALUE + "0";
        final Schema schema = new IntegerSchema().format("int64");

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.format.int64");
    }

    @Test
    public void validate_withInvalidFloatFormat_shouldFail() {
        final String value = "1" + Float.MAX_VALUE;
        final Schema schema = new NumberSchema().format("float");

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.format.float");
    }

    @Test
    public void validate_withInvalidDoubleFormat_shouldFail() {
        final String value = "1" + Double.MAX_VALUE;
        final Schema schema = new NumberSchema().format("double");

        // A value outside the double range cannot be represented as a JSON number that the
        // validator accepts: networknt 3.x rejects it as a type error ("number expected") before
        // the custom double-format validator is reached. The value is still rejected.
        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.type");
    }

    @Test
    public void validate_shouldFail_whenInvalidJson() {
        final String value = "#";
        final Schema schema = new Schema();

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.invalidJson");
    }

    @Test
    public void validate_withValidModel_shouldPass_whenModelInline() {
        final String value = "{\"foo\":\"bar\"}";
        final Schema schema = new ObjectSchema()
                .addProperties("foo", new StringSchema())
                .required(singletonList("foo"));

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withInvalidModel_shouldFail_whenModelInline() {
        final String value = "{\"foos\":\"bar\"}";
        final Schema schema = new ObjectSchema()
                .addProperties("foo", new StringSchema())
                .required(singletonList("foo"));

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.required");
    }

    @Test
    public void validate_withValidModel_shouldPass_whenModelReferenced() {
        final String value = "{\"title\":\"bar\", \"message\":\"something\"}";
        final Schema schema = new Schema().$ref("#/components/schemas/Error");

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withInvalidModel_shouldFail_whenModelReferenced() {
        final String value = "{\"title\":\"bar\"}";
        final Schema schema = new Schema().$ref("#/components/schemas/Error");

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.required");
    }

    @Test
    public void validate_withExtraFields_shouldFail_whenModelReferenced() {
        final String value = "{\"title\":\"bar\", \"message\":\"something\", \"extra\":\"value\"}";
        final Schema schema = new Schema().$ref("#/components/schemas/Error");

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.additionalProperties");
    }

    @Test
    public void validate_withExtraFields_shouldFail_whenModelInline() {
        final String value = "{\"foo\":\"bar\", \"extra\":\"value\"}";
        final Schema schema = new ObjectSchema()
                .addProperties("foo", new StringSchema())
                .required(singletonList("foo"));

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.additionalProperties");
    }

    @Test
    public void validate_withExtraFields_shouldFail_whenModelInlineInArray() {
        final String value = "{\"things\": [{\"foo\":\"bar\", \"extra\":\"value\"}]}";
        final Schema inner = new ObjectSchema().addProperties("foo", new StringSchema()).required(singletonList("foo"));
        final Schema schema = new ObjectSchema().addProperties("things", new ArraySchema().items(inner));

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.additionalProperties");
    }

    @Test
    public void validate_withExtraFields_shouldFail_whenModelInlineInObject() {
        final String value = "{\"things\": {\"foo\":\"bar\", \"extra\":\"value\"}}";
        final Schema inner = new ObjectSchema().addProperties("foo", new StringSchema()).required(singletonList("foo"));
        final Schema schema = new ObjectSchema().addProperties("things", inner);

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.additionalProperties");
    }

    @Test
    public void validate_withExtraFields_shouldFail_whenDeepNesting() {
        final String value = "{\"outer\": {\"inner\": {\"innermost\": {\"field\": \"value\", \"extra\": \"value\"}}}}";
        final Schema schema = new ObjectSchema()
                .addProperties("outer", new ObjectSchema()
                        .addProperties("inner", new ObjectSchema()
                                .addProperties("innermost", new ObjectSchema()
                                        .addProperties("field", new ObjectSchema())
                                )
                        )
                );

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.additionalProperties");
    }

    @Test
    public void validate_withInvalidJsonSchema_shouldFail() {
        final String value = "{\"title\":\"bar\", \"message\":\"something\"}";
        final Schema schema = new Schema().$ref("#/definitions/{\"What\":\"This actually happened!\"}");

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.processingError");
    }

    @Test
    public void validate_withOtherException_shouldFail() {
        final OpenAPI mockApi = mock(OpenAPI.class);
        when(mockApi.getComponents()).thenThrow(new IllegalStateException("Testing exception handling"));
        assertThrows(IllegalStateException.class, () -> new SchemaValidator(mockApi, new MessageResolver(),
                new ValidationConfiguration()));
    }

    @Test
    public void validate_withJsonSchemaComposition_shouldWork_whenAdditionalPropertyValidationIgnored() {

        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v2/api-composition.yaml");

        final Schema schema = new Schema().$ref("#/components/schemas/User");
        final String value = "{\"firstname\":\"user_firstname\", \"lastname\":\"user_lastname\", \"city\":\"user_city\"}";

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withAllOf_shouldAddInfoOnNestedFailures_whenSubSchemaValidationFails() {

        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v2/api-composition.yaml");

        final Schema schema = new Schema().$ref("#/components/schemas/User");
        final String value = "{\"firstname\":\"user_firstname\", \"city\":1}";

        final ValidationReport report = classUnderTest.validate(value, schema, "prefix");
        assertFailWithoutContext(report, "validation.prefix.schema.required", "validation.prefix.schema.type");
    }

    @Test
    public void validate_withJsonSchemaComposition_shouldPass_whenAdditionalPropertyValidationNotIgnored() {

        final SchemaValidator classUnderTest = validatorWithResolveCombinators("/oai/v3/api-composition.yaml");

        final Schema schema = new Schema().$ref("#/components/schemas/User");
        final String value = "{\"firstname\":\"user_firstname\", \"lastname\":\"user_lastname\", \"city\":\"user_city\"}";

        final ValidationReport report = classUnderTest.validate(value, schema, "prefix");
        assertPass(report);
    }

    @Test
    public void validate_withJsonSchemaComposition_shouldFail_whenAdditionalPropertyValidationNotIgnored_andUndefinedPropertyReturnedInResponse() {

        final SchemaValidator classUnderTest = validatorWithResolveCombinators("/oai/v3/api-composition.yaml");

        final Schema schema = new Schema().$ref("#/components/schemas/User");
        final String value = "{\"firstname\":\"user_firstname\", \"lastname\":\"user_lastname\", \"city\":\"user_city\", \"zip\":\"97201\"}";

        final ValidationReport report = classUnderTest.validate(value, schema, "prefix");
        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.additionalProperties");
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNullValues() {
        final String value =
                "{\"foo\":\"bar\"," +
                        "\"baz\": null," +
                        "\"int\": null," +
                        "\"obj\":{\"obj1\": null, \"obj2\": null, \"obj3\": \"val3\"}," +
                        "\"arr\":[null, \"val1\", \"val2\"]}";
        final Schema schema = new ObjectSchema()
                .addProperties("foo", new StringSchema())
                .addProperties("baz", new StringSchema().nullable(true))
                .addProperties("int", new IntegerSchema().nullable(true))
                .addProperties("obj", new ObjectSchema()
                        .addProperties("obj1", new StringSchema().nullable(true))
                        .addProperties("obj2", new StringSchema().nullable(true))
                        .addProperties("obj3", new StringSchema().nullable(true))
                )
                .addProperties("arr", new ArraySchema().items(new StringSchema().nullable(true)))
                .required(singletonList("foo"));

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNullValues_inArray() {
        final String value = "{\"arr\": [1, 2, null, 3]}";
        final Schema schema = new ObjectSchema()
                .addProperties("arr", new ArraySchema().items(new IntegerSchema().nullable(true)));

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNullValues_inObjects_inArrays() {
        final String value =
                "{\"arr\": [" +
                        "{\"int\":null}," +
                        "{\"str\":null}," +
                        "{\"flt\":null}" +
                        "]}";

        final Schema schema = new Schema()
                .addProperties("arr", new ArraySchema().items(
                        new ObjectSchema()
                                .addProperties("int", new IntegerSchema().nullable(true))
                                .addProperties("str", new StringSchema().nullable(true))
                                .addProperties("flt", new NumberSchema().format("float").nullable(true))
                ));

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNullValues_inNullableArrayItem() {
        final String value =
                "[ null ]";

        final Schema schema = new ArraySchema().items(new IntegerSchema().nullable(true));

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNonNullValues_inNullableArrayItem() {
        final String value =
                "[ 1 ]";

        final Schema schema = new ArraySchema().items(new IntegerSchema().nullable(true));

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNonNullValues_inNonNullableArrayItem() {
        final String value =
                "[ 1 ]";

        final Schema schema = new ArraySchema().items(new IntegerSchema().nullable(false));

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldFail_whenContainsNullValues_inUnnullableArrayItem() {
        final String value =
                "[ null ]";

        final Schema schema = new ArraySchema().items(new IntegerSchema().nullable(false));

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNullValues_inNullableObjectProperty() {
        final String value =
                "{\"int\": null }";

        final Schema schema = new Schema()
                .addProperties("int", new IntegerSchema().nullable(true));

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNonNullValues_inNullableObjectProperty() {
        final String value =
                "{\"int\": 1 }";

        final Schema schema = new Schema()
                .addProperties("int", new IntegerSchema().nullable(true));

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNullValues_inNullableRequiredObjectProperty() {
        final String value =
                "{\"int\": null }";

        final Schema schema = new Schema()
                .addProperties("int", new IntegerSchema().nullable(true))
                .addRequiredItem("int");

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldPass_whenContainsNonNullValues_inNullableRequiredObjectProperty() {
        final String value =
                "{\"int\": 1 }";

        final Schema schema = new Schema()
                .addProperties("int", new IntegerSchema().nullable(true))
                .addRequiredItem("int");

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldFail_whenContainsNullValues_inUnnullableObjectProperty() {
        final String value =
                "{\"int\": null }";

        final Schema schema = new Schema()
                .addProperties("int", new IntegerSchema().nullable(false));

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withValidModel_shouldFail_whenContainsNullValues_inUnnullableRequiredObjectProperty() {
        final String value =
                "{\"int\": null }";

        final Schema schema = new Schema()
                .addProperties("int", new IntegerSchema().nullable(false))
                .addRequiredItem("int");

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withDiscriminator_shouldPass_whenValid() {

        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v2/api-discriminator.yaml");
        final Schema schema = new Schema().$ref("#/components/schemas/Pet");
        final String value = "{\"name\": \"Moggy\", \"petType\": \"Cat\", \"huntingSkill\":\"clueless\"}";

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withDiscriminator_shouldPass_everyTime_whenInvokedMultipleTimes() {

        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v2/api-discriminator.yaml");
        final Schema schema = new Schema().$ref("#/components/schemas/Pet");
        final String value = "{\"name\": \"Moggy\", \"petType\": \"Cat\", \"huntingSkill\":\"clueless\"}";

        assertPass(classUnderTest.validate(value, schema, "prefix"));
        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Disabled("Ignore this test because networknt does not support such discriminator validation")
    @Test
    public void validate_withDiscriminator_shouldFail_whenInvalid() {

        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v2/api-discriminator.yaml");
        final Schema schema = new Schema().$ref("#/components/schemas/Pet");
        final String value = "{\"name\": \"Moggy\", \"petType\": \"Cat\", \"huntingSkill\":\"ruthless\"}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.discriminator");
    }

    @Disabled("Ignore this test because networknt does not support such discriminator validation")
    @Test
    public void validate_withDiscriminator_shouldFail_everyTime_whenInvokedMultipleTimes() {

        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v2/api-discriminator.yaml");
        final Schema schema = new Schema().$ref("#/components/schemas/Pet");
        final String value = "{\"name\": \"Moggy\", \"petType\": \"Cat\", \"huntingSkill\":\"ruthless\"}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.discriminator");
        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.discriminator");
        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.discriminator");
        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.discriminator");
        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.discriminator");
    }

    @Test
    public void validate_withEnumDiscriminator_shouldPass_whenValid() {

        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v2/api-discriminator-enum.yaml");
        final Schema schema = new Schema().$ref("#/components/schemas/Pet");
        final String value = "{\"name\": \"Moggy\", \"petType\": \"Cat\", \"huntingSkill\":\"clueless\"}";

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withDateProperty_shouldPass_whenValid() {
        final String value = "1985-04-12";
        final Schema schema = new DateSchema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withDateProperty_shouldFail_whenInvalid() {
        final String value = "1985-99-99";
        final Schema schema = new DateSchema();

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withDateTimeProperty_shouldPass_whenValid() {
        final String value = "1985-04-12T23:20:50.52Z";
        final Schema schema = new DateTimeSchema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withDateTimeProperty_shouldPass_whenExampleIncluded() {
        final String value = "1985-04-12T23:20:50.52Z";
        final Schema schema = new DateTimeSchema()
                .example("1937-01-01T12:00:27.87+00:20");

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withDateTimeProperty_shouldPass_withTimezone() {
        final String value = "1990-12-31T15:59:59+08:00";
        final Schema schema = new DateTimeSchema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withDateTimeProperty_shouldFail_withWrongFormat() {
        final String value = "Wed Jul 19 14:21:33 UTC 2017";
        final Schema schema = new DateTimeSchema();

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.format.date-time");
    }

    @Test
    public void validate_withNoDefinitionsBlock_shouldPass_whenValid() {
        final SchemaValidator classUnderTest = validator("/oai/v2/api-no-definitions.json");

        final String value = "{\"id\":123}";
        final Schema schema = new ObjectSchema().addProperties("id", new IntegerSchema());

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withNoComponentsBlock_shouldPass_whenValid() {
        final SchemaValidator classUnderTest = validator("/oai/v3/api-no-components.yaml");

        final String value = "{\"id\":123}";
        final Schema schema = new ObjectSchema().addProperties("id", new IntegerSchema());

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withNumberProperty_shouldPass_whenValid() {
        final String value = "1";
        final Schema schema = new NumberSchema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withNumberProperty_shouldFail_withWrongType_whenInvalid() {
        final String value = "1,2";
        final Schema schema = new NumberSchema();

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.type");
    }

    @Test
    public void validate_withUuidProperty_shouldPass_whenValid() {
        final String value = UUID.randomUUID().toString();
        final Schema schema = new UUIDSchema();

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validate_withUuidProperty_shouldFail_whenInvalid() {
        final String value = UUID.randomUUID().toString() + "}";
        final Schema schema = new UUIDSchema();

        assertFailWithoutContext(classUnderTest.validate(value, schema, "prefix"),
                "validation.prefix.schema.format.uuid");
    }

    @Test
    public void validate_readOnly_isRequired_inResponse() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnly");

        final String value = "{\"notReadOnly\":\"abc\", \"writeOnly\": \"123\"}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "response.body"),
                "validation.response.body.schema.required");
    }

    @Test
    public void validate_readOnly_isNotRequired_inRequest() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnly");

        final String value = "{\"notReadOnly\":\"abc\", \"writeOnly\": \"123\"}";

        assertPass(classUnderTest.validate(value, schema, "request.body"));
    }

    @Test
    public void validate_readOnly_isRequired_inOtherLocation() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnly");

        final String value = "{\"notReadOnly\":\"abc\", \"writeOnly\": \"123\"}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "query"),
                "validation.query.schema.required");
    }

    @Test
    public void validate_readOnly_isRequired_withNesting_inResponse() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyNested");

        final String value = "{\"nestedRef\": {\"notReadOnly\":\"abc\", \"writeOnly\": \"123\"}, \"nestedInline\": {\"notReadOnly\":\"abc\", \"writeOnly\": \"123\"}}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "response.body"),
                "validation.response.body.schema.required");
    }

    @Test
    public void validate_readOnly_isNotRequired_withNesting_inRequest() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyNested");

        final String value = "{\"nestedRef\": {\"notReadOnly\":\"abc\", \"writeOnly\": \"123\"}, \"nestedInline\": {\"notReadOnly\":\"abc\", \"writeOnly\": \"123\"}}";

        assertPass(classUnderTest.validate(value, schema, "request.body"));
    }

    @Test
    public void validate_readOnly_isRequired_withArray_inResponse() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyArray");

        final String value = "[{\"notReadOnly\":\"abc\", \"writeOnly\": \"123\"}]";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "response.body"),
                "validation.response.body.schema.required");
    }

    @Test
    public void validate_readOnly_isNotRequired_withArray_inRequest() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyArray");

        final String value = "[{\"notReadOnly\":\"abc\", \"writeOnly\": \"123\"}]";

        assertPass(classUnderTest.validate(value, schema, "request.body"));
    }

    @Test
    public void validate_readOnly_isRequired_withAllOfComposition_inResponse() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyAllOf");

        final String value = "{\"id\": \"test\", \"notReadOnly\":\"abc\", \"writeOnly\":\"123\"}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "response.body"),
                "validation.response.body.schema.required");
    }

    @Test
    public void validate_readOnly_isNotRequired_withAllOfComposition_inRequest() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyAllOf");

        final String value = "{\"id\": \"test\", \"notReadOnly\":\"abc\", \"writeOnly\":\"123\"}";

        assertPass(classUnderTest.validate(value, schema, "request.body"));
    }

    @Test
    public void validate_writeOnly_isRequired_inRequest() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnly");

        final String value = "{\"notReadOnly\":\"abc\", \"readOnly\": \"123\"}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "request.body"),
                "validation.request.body.schema.required");
    }

    @Test
    public void validate_writeOnly_isNotRequired_inResponse() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnly");

        final String value = "{\"notReadOnly\":\"abc\", \"readOnly\": \"123\"}";

        assertPass(classUnderTest.validate(value, schema, "response.body"));
    }

    @Test
    public void validate_writeOnly_isRequired_inOtherLocation() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnly");

        final String value = "{\"notReadOnly\":\"abc\", \"readOnly\": \"123\"}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "query"),
                "validation.query.schema.required");
    }

    @Test
    public void validate_writeOnly_isRequired_withNesting_inRequest() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyNested");

        final String value = "{\"nestedRef\": {\"notReadOnly\":\"abc\", \"readOnly\": \"123\"}, \"nestedInline\": {\"notReadOnly\":\"abc\", \"readOnly\": \"123\"}}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "request.body"),
                "validation.request.body.schema.required");
    }

    @Test
    public void validate_writeOnly_isNotRequired_withNesting_inResponse() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyNested");

        final String value = "{\"nestedRef\": {\"notReadOnly\":\"abc\", \"readOnly\": \"123\"}, \"nestedInline\": {\"notReadOnly\":\"abc\", \"readOnly\": \"123\"}}";

        assertPass(classUnderTest.validate(value, schema, "response.body"));
    }

    @Test
    public void validate_writeOnly_isRequired_withArray_inRequest() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyArray");

        final String value = "[{\"notReadOnly\":\"abc\", \"readOnly\": \"123\"}]";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "request.body"),
                "validation.request.body.schema.required");
    }

    @Test
    public void validate_writeOnly_isNotRequired_withArray_inResponse() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyArray");

        final String value = "[{\"notReadOnly\":\"abc\", \"readOnly\": \"123\"}]";

        assertPass(classUnderTest.validate(value, schema, "response.body"));
    }

    @Test
    public void validate_writeOnly_isRequired_withAllOfComposition_inRequest() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyAllOf");

        final String value = "{\"id\": \"test\", \"notReadOnly\":\"abc\", \"readOnly\":\"123\"}";

        assertFailWithoutContext(classUnderTest.validate(value, schema, "request.body"),
                "validation.request.body.schema.required");
    }

    @Test
    public void validate_writeOnly_isNotRequired_withAllOfComposition_inResponse() {
        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-required-readonly-writeonly.yaml");
        final Schema schema = getSchemasFrom("/oai/v3/api-required-readonly-writeonly.yaml").get("ReadOnlyAllOf");

        final String value = "{\"id\": \"test\", \"notReadOnly\":\"abc\", \"readOnly\":\"123\"}";

        assertPass(classUnderTest.validate(value, schema, "response.body"));
    }

    @Test
    public void validate_nestedProperties() {

        final SchemaValidator classUnderTest = validatorWithAdditionalPropertiesIgnored("/oai/v3/api-with-deeply-nested-elements.yaml");

        final Schema schema = getSchemasFrom("/oai/v3/api-with-deeply-nested-elements.yaml").get("Pet");

        final String deeplyNested = "{\"details\": { \"type\": \"Doggo\", \"breed\": \"lappie\", \"colour\": \"tan\" } }";
        final String shallowNested = "{\"sibling\": { \"type\": \"Doggo\", \"breed\": \"lappie\" } }";

        final ValidationReport reportShallow = classUnderTest.validate(shallowNested, schema, "response.body");
        final ValidationReport reportDeep = classUnderTest.validate(deeplyNested, schema, "response.body");

        assertFailWithoutContext(reportShallow);
        assertFailWithoutContext(reportDeep);

        final String expectedSimpleFormatError = "validation.response.body.schema.enum";
        assertTrue(reportShallow.getMessages().stream().anyMatch(m -> m.getKey().equals(expectedSimpleFormatError)));
        assertTrue(reportDeep.getMessages().stream().anyMatch(m -> m.getKey().equals(expectedSimpleFormatError)));

        final String expectedJsonFormatError = "validation.response.body.schema.enum";
        assertTrue(reportShallow.getMessages().stream().anyMatch(m -> m.getKey().equals(expectedJsonFormatError)));
        assertTrue(reportDeep.getMessages().stream().anyMatch(m -> m.getKey().equals(expectedJsonFormatError)));
    }

    @Test
    public void validateJsonNode_withEmptyJsonNodeAndEmptySchema_shouldPass() {
        final JsonNode value = new ObjectMapper().createObjectNode();
        final Schema schema = new Schema();

        assertPass(classUnderTest.validate(() -> value, schema, "prefix"));
    }

    @Test
    public void validate_nonRequired_minLength_stringProperty_shouldPass() {
        final String value = "{\"requiredField\":\"foo\"}";
        final Schema schema = new ObjectSchema()
                .addProperties("requiredField", new StringSchema().minLength(1))
                .addProperties("nonRequiredField", new StringSchema().minLength(3))
                .addRequiredItem("requiredField");

        assertPass(classUnderTest.validate(value, schema, "prefix"));
    }

    @Test
    public void validateDisableJsonSchemaCache() throws NoSuchFieldException, IllegalAccessException {
        final String value = "{\"requiredField\":\"foo\"}";
        final Schema schema = new ObjectSchema()
            .addProperties("requiredField", new StringSchema().minLength(1))
            .addProperties("nonRequiredField", new StringSchema().minLength(3))
            .addRequiredItem("requiredField");
        final SchemaValidator tester = validatorWithCacheSize("/oai/v2/api-users.json", 0);
        final Field jsonSchemaField = SchemaValidator.class.getDeclaredField("jsonSchemaCache");
        jsonSchemaField.setAccessible(true);

        assertPass(tester.validate(value, schema, "prefix"));
        final LoadingCache jsonSchemaCache = (LoadingCache) jsonSchemaField.get(tester);
        assertNull(jsonSchemaCache);

    }

    @Test
    public void validateEnableJsonSchemaCache() throws NoSuchFieldException, IllegalAccessException {
        final String value = "{\"requiredField\":\"foo\"}";
        final Schema schema = new ObjectSchema()
            .addProperties("requiredField", new StringSchema().minLength(1))
            .addProperties("nonRequiredField", new StringSchema().minLength(3))
            .addRequiredItem("requiredField");

        final SchemaValidator tester = validatorWithCacheSize("/oai/v2/api-users.json", 3);
        final Field jsonSchemaField = SchemaValidator.class.getDeclaredField("jsonSchemaCache");
        jsonSchemaField.setAccessible(true);

        assertPass(tester.validate(value, schema, "prefix"));
        final LoadingCache jsonSchemaCache1 = (LoadingCache) jsonSchemaField.get(tester);
        assertNotNull(jsonSchemaCache1);
        assertEquals(jsonSchemaCache1.estimatedSize(), 1);

        final String value1 = "{\"foo\":\"bar\"}";
        final Schema schema1 = new ObjectSchema()
            .addProperties("foo", new StringSchema())
            .required(singletonList("foo"));
        assertPass(tester.validate(value1, schema1, "prefix"));
        final LoadingCache jsonSchemaCache2 = (LoadingCache) jsonSchemaField.get(tester);
        assertNotNull(jsonSchemaCache2);
        assertEquals(jsonSchemaCache2.estimatedSize(), 2);
    }

    private Map<String, Schema> getSchemasFrom(final String api) {
        final ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolveFully(true);
        return new OpenAPIParser().readLocation(api, null, parseOptions).getOpenAPI().getComponents().getSchemas();
    }

    private SchemaValidator validatorWithAdditionalPropertiesIgnored(final String api) {
        final ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        return new SchemaValidator(
                new OpenAPIParser().readLocation(api, null, parseOptions).getOpenAPI(),
                new MessageResolver(
                        LevelResolver
                                .create()
                                .withLevel(ADDITIONAL_PROPERTIES_KEY, ValidationReport.Level.IGNORE)
                                .build())
        );
    }

    private SchemaValidator validator(final String api) {
        final ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        final OpenAPI parsedApi = new OpenAPIParser().readLocation(api, null, parseOptions).getOpenAPI();
        final MessageResolver messageResolver = new MessageResolver();
        final ValidationConfiguration validationConfiguration = new ValidationConfiguration();

        return new SchemaValidator(parsedApi, messageResolver, validationConfiguration);
    }

    private SchemaValidator validatorWithCacheSize(final String api, final int cacheSize) {
        final ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        final ValidationConfiguration validationConfiguration = new ValidationConfiguration();
        validationConfiguration.setMaxCacheSize(cacheSize);
        return new SchemaValidator(new OpenAPIParser().readLocation(api, null, parseOptions).getOpenAPI(), new MessageResolver(),
            validationConfiguration);
    }

    private SchemaValidator validatorWithResolveCombinators(final String api) {
        final ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolveFully(true);
        parseOptions.setResolveCombinators(true);
        return new SchemaValidator(new OpenAPIParser().readLocation(api, null, parseOptions).getOpenAPI(), new MessageResolver());
    }
}
