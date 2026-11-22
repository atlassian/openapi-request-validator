package com.atlassian.oai.validator;

import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import org.junit.jupiter.api.Test;

import static com.atlassian.oai.validator.util.ValidatorTestUtil.assertFail;
import static com.atlassian.oai.validator.util.ValidatorTestUtil.assertPass;

/**
 * OpenAPI 3.1 parses array schemas as generic schemas (with the type held in {@code getTypes()}) rather than
 * as {@code ArraySchema}. Exploded array query parameters must still be validated as arrays.
 */
public class OpenApiV31ExplodedArrayParamValidationTest {

    private final OpenApiInteractionValidator classUnderTest =
            OpenApiInteractionValidator.createForSpecificationUrl("/oai/v3/api-exploded-array-31.yaml").build();

    @Test
    public void explodedArrayParam_withSingleValue_shouldPass() {
        final Request request = SimpleRequest.Builder
                .get("/items")
                .withQueryParam("sort", "field")
                .build();

        assertPass(classUnderTest.validateRequest(request));
    }

    @Test
    public void explodedArrayParam_withSingleValueContainingComma_shouldPass() {
        final Request request = SimpleRequest.Builder
                .get("/items")
                .withQueryParam("sort", "field,DESC")
                .build();

        assertPass(classUnderTest.validateRequest(request));
    }

    @Test
    public void explodedArrayParam_withMultipleValues_shouldPass() {
        final Request request = SimpleRequest.Builder
                .get("/items")
                .withQueryParam("sort", "field", "other,ASC")
                .build();

        assertPass(classUnderTest.validateRequest(request));
    }

    @Test
    public void explodedArrayParam_withNoValues_shouldPass_whenOptional() {
        final Request request = SimpleRequest.Builder
                .get("/items")
                .build();

        assertPass(classUnderTest.validateRequest(request));
    }

    @Test
    public void explodedArrayParam_shouldFail_whenSingleValueDoesNotMatchItemSchema() {
        final Request request = SimpleRequest.Builder
                .get("/items")
                .withQueryParam("sort", "not valid!")
                .build();

        assertFail(classUnderTest.validateRequest(request), "validation.request.parameter.schema.pattern");
    }

    @Test
    public void explodedArrayParam_shouldFail_whenOneOfMultipleValuesDoesNotMatchItemSchema() {
        final Request request = SimpleRequest.Builder
                .get("/items")
                .withQueryParam("sort", "field", "not valid!")
                .build();

        assertFail(classUnderTest.validateRequest(request), "validation.request.parameter.schema.pattern");
    }

    @Test
    public void explodedArrayParam_withIntegerItems_shouldPass_whenValid() {
        final Request request = SimpleRequest.Builder
                .get("/items")
                .withQueryParam("ids", "1", "2", "3")
                .build();

        assertPass(classUnderTest.validateRequest(request));
    }

    @Test
    public void explodedArrayParam_withIntegerItems_shouldFail_whenItemIsNotAnInteger() {
        final Request request = SimpleRequest.Builder
                .get("/items")
                .withQueryParam("ids", "1", "abc")
                .build();

        assertFail(classUnderTest.validateRequest(request), "validation.request.parameter.schema.type");
    }

    @Test
    public void explodedArrayParam_shouldFail_whenTooManyItems() {
        final Request request = SimpleRequest.Builder
                .get("/items")
                .withQueryParam("ids", "1", "2", "3", "4")
                .build();

        assertFail(classUnderTest.validateRequest(request), "validation.request.parameter.collection.tooManyItems");
    }
}
