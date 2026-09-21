package com.atlassian.oai.validator.model;

import org.junit.jupiter.api.Test;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ApiPathImplTest {

    @Test
    public void no_params_orNormalization() {
        final ApiPathImpl classUnderTest = new ApiPathImpl("/p1/p2/p3", null);

        assertThat(classUnderTest.numberOfParts(), is(3));
        assertThat(classUnderTest.normalised(), is("/p1/p2/p3"));
        assertThat(classUnderTest.original(), is("/p1/p2/p3"));

        assertThat(classUnderTest.hasParams(0), is(false));
        assertThat(classUnderTest.paramNames(0), is(empty()));

        assertThat(classUnderTest.hasParams(1), is(false));
        assertThat(classUnderTest.paramNames(1), is(empty()));

        assertThat(classUnderTest.hasParams(2), is(false));
        assertThat(classUnderTest.paramNames(2), is(empty()));
    }

    @Test
    public void normalization_addsLeadingSlash() {
        final ApiPathImpl classUnderTest = new ApiPathImpl("p1/p2/p3", null);

        assertThat(classUnderTest.numberOfParts(), is(3));
        assertThat(classUnderTest.normalised(), is("/p1/p2/p3"));
        assertThat(classUnderTest.original(), is("p1/p2/p3"));
    }

    @Test
    public void apiPrefix_isRemoved() {
        final ApiPathImpl classUnderTest = new ApiPathImpl("p1/p2/p3", "p1");

        assertThat(classUnderTest.numberOfParts(), is(2));
        assertThat(classUnderTest.normalised(), is("/p2/p3"));
        assertThat(classUnderTest.original(), is("p1/p2/p3"));
    }

    @Test
    public void pathParamsIdentified_whenWholePathPart() {
        final ApiPathImpl classUnderTest = new ApiPathImpl("p1/{param1}/p3", null);

        assertThat(classUnderTest.numberOfParts(), is(3));
        assertThat(classUnderTest.normalised(), is("/p1/{param1}/p3"));
        assertThat(classUnderTest.original(), is("p1/{param1}/p3"));

        assertThat(classUnderTest.hasParams(0), is(false));
        assertThat(classUnderTest.paramNames(0), is(empty()));

        assertThat(classUnderTest.hasParams(1), is(true));
        assertThat(classUnderTest.paramNames(1), contains("param1"));

        assertThat(classUnderTest.hasParams(2), is(false));
        assertThat(classUnderTest.paramNames(2), is(empty()));
    }

    @Test
    public void pathParamsIdentified_whenPartPathPart() {
        final ApiPathImpl classUnderTest = new ApiPathImpl("p1/p2/{param1}.json", null);

        assertThat(classUnderTest.hasParams(0), is(false));
        assertThat(classUnderTest.paramNames(0), is(empty()));

        assertThat(classUnderTest.hasParams(1), is(false));
        assertThat(classUnderTest.paramNames(1), is(empty()));

        assertThat(classUnderTest.hasParams(2), is(true));
        assertThat(classUnderTest.paramNames(2), contains("param1"));
    }

    @Test
    public void pathParamsIdentified_whenMultipleParamsInOnePart() {
        final ApiPathImpl classUnderTest = new ApiPathImpl("p1/p2/{param1}-{param2}.json", null);

        assertThat(classUnderTest.hasParams(0), is(false));
        assertThat(classUnderTest.paramNames(0), is(empty()));

        assertThat(classUnderTest.hasParams(1), is(false));
        assertThat(classUnderTest.paramNames(1), is(empty()));

        assertThat(classUnderTest.hasParams(2), is(true));
        assertThat(classUnderTest.paramNames(2), contains("param1", "param2"));
    }

    @Test
    public void paramValues_canExtractParamValues_fromParam_whenWholePathPart() {
        final String[] expected = {"foop"};
        testParamValueExtraction("{param1}", "foop", expected);
    }

    @Test
    public void paramValues_canExtractParamValues_fromParam_whenPartPathPart_singleParam() {
        final String[] expected = {"foop"};
        testParamValueExtraction("{param1}.json", "foop.json", expected);
    }

    @Test
    public void paramValues_canExtractParamValues_fromParam_whenPartPathPart_multipleParams() {
        final String[] expected = {"foop", "b", "blarp"};
        testParamValueExtraction("{param1}-{param2}:{param3}", "foop-b:blarp", expected);
    }

    @Test
    public void paramValues_canExtractParamValues_fromParam_whenPartPathPart_reservedCharacters() {
        final String[] expected = {":fo/op[", "bl arp+"};
        testParamValueExtraction("{param1}-{param2}", "%3Afo%2Fop%5B-bl%20arp%2B", expected);
    }

    @Test
    public void paramValues_addsEmpty_whenMissingParamValues() {
        final String[] expected = {"foop", null};
        testParamValueExtraction("{param1}-{param2}", "foop-", expected);
    }

    @Test
    public void paramValues_handlesBadTemplate() {
        final String[] expected = {"foop"};
        testParamValueExtraction("{param1}-{param2", "foop-blarp", expected);
    }

    @Test
    public void paramValues_isCaseInsensitive() {
        final String[] expected = {"foop", "plop"};
        testParamValueExtraction("{param1}.BlARp.{param2}", "foop.blarp.plop", expected);
    }

    @Test
    public void partMatches_matches_whenLiteralMatch() {
        assertThat(partMatches("foop", "foop"), is(true));
    }

    @Test
    public void partMatches_matches_caseInsensitive() {
        assertThat(partMatches("foop", "FOoP"), is(true));
    }

    @Test
    public void partMatches_matches_whenWholePartParam() {
        assertThat(partMatches("{param1}", "foop"), is(true));
    }

    @Test
    public void partMatches_matches_whenPartialPartParam() {
        assertThat(partMatches("{param1}.json", "foop.json"), is(true));
    }

    @Test
    public void partMatches_matches_whenMultiplePartParam() {
        assertThat(partMatches("{param1}.{param2}", "foop.json"), is(true));
    }

    @Test
    public void partMatches_doesNotMatch_whenNoParams_andNoMatch() {
        assertThat(partMatches("foop", "blarp"), is(false));
    }

    @Test
    public void partMatches_doesNotMatch_whenPartialPartParams_andNoMatch() {
        assertThat(partMatches("{param1}.json", "foop.html"), is(false));
    }

    @Test
    public void matches_matches_whenLiteralPath() {
        assertThat(matches("/p1/p2/p3", "/p1/p2/p3"), is(true));
    }

    @Test
    public void matches_matches_caseInsensitive() {
        assertThat(matches("/p1/P2/p3", "/p1/p2/P3"), is(true));
    }

    @Test
    public void matches_matches_whenPathParams() {
        assertThat(matches("/p1/{param1}/p3/{param2}-{param3}.json", "/p1/p2/P3/foop-blarp.json"), is(true));
    }

    @Test
    public void matches_matches_whenTrailingSlashDifferent() {
        assertThat(matches("/p1", "/p1/"), is(true));
    }

    @Test
    public void matches_matches_whenTrailingSlashSameWithStrict() {
        assertThat(matchesStrict("/p1/", "/p1/"), is(true));
    }

    @Test
    public void matches_doesNotMatches_whenTrailingSlashDifferentWithStrict() {
        assertThat(matchesStrict("/p1", "/p1/"), is(false));
    }

    @Test
    public void matches_doesNotMatches_whenBadLiteralMatch() {
        assertThat(matches("/p1/p2/p3", "/p1/p2/floop"), is(false));
    }

    @Test
    public void matches_doesNotMatches_whenBadParamMatch() {
        assertThat(matches("/p1/p2/{param1}-{param2}", "/p1/p2/floop"), is(false));
    }

    @Test
    public void partMatches_matchesLiterally_whenPartContainsRegexMetacharacters() {
        assertThat(partMatches("a.b", "a.b"), is(true));
        assertThat(partMatches("a.b", "axb"), is(false));
        assertThat(partMatches("a+b", "a+b"), is(true));
        assertThat(partMatches("a+b", "aab"), is(false));
    }

    @Test
    public void partMatches_throws_whenIndexOutOfRange() {
        final ApiPathImpl classUnderTest = new ApiPathImpl("/{id}", null);

        assertThrows(IndexOutOfBoundsException.class, () -> classUnderTest.partMatches(1, "foop"));
    }

    @Test
    public void partMatches_isRepeatable_onTheSameInstance() {
        final ApiPathImpl classUnderTest = new ApiPathImpl("/{id}/literal/a.b/a+b", null);

        assertThat(classUnderTest.partMatches(0, "foop"), is(true));
        assertThat(classUnderTest.partMatches(1, "literal"), is(true));
        assertThat(classUnderTest.partMatches(1, "LITERAL"), is(true));
        assertThat(classUnderTest.partMatches(1, "blarp"), is(false));
        assertThat(classUnderTest.partMatches(2, "a.b"), is(true));
        assertThat(classUnderTest.partMatches(2, "axb"), is(false));
        assertThat(classUnderTest.partMatches(3, "a+b"), is(true));
        assertThat(classUnderTest.partMatches(3, "aab"), is(false));
    }

    @Test
    public void matches_isRepeatable_onTheSameInstance() {
        final ApiPathImpl classUnderTest = new ApiPathImpl("/p1/{param1}/p3", null);

        assertThat(classUnderTest.matches(new NormalisedPathImpl("/p1/foop/p3", null)), is(true));
        assertThat(classUnderTest.matches(new NormalisedPathImpl("/p1/foop/P3", null)), is(true));
        assertThat(classUnderTest.matches(new NormalisedPathImpl("/p1/foop/blarp", null)), is(false));
    }

    private static void testParamValueExtraction(final String expression, final String path, final String... expected) {
        assertThat(new ApiPathImpl(expression, null).paramValues(0, path).values(),
                containsInAnyOrder(stream(expected).map(e -> is(ofNullable(e))).collect(toList())));
    }

    private static boolean partMatches(final String expression, final String pathPart) {
        return new ApiPathImpl(expression, null).partMatches(0, pathPart);
    }

    private static boolean matches(final String expression, final String path) {
        return new ApiPathImpl(expression, null).matches(new NormalisedPathImpl(path, null));
    }

    private static boolean matchesStrict(final String expression, final String path) {
        return new ApiPathImpl(expression, null, true).matches(new NormalisedPathImpl(path, null));
    }
}
