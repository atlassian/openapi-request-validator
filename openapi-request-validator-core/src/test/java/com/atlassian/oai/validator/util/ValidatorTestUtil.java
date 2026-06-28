package com.atlassian.oai.validator.util;

import com.atlassian.oai.validator.report.JsonValidationReportFormat;
import com.atlassian.oai.validator.report.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.lang.String.format;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ValidatorTestUtil {

    private ValidatorTestUtil() {
    }

    private static final Logger log = LoggerFactory.getLogger(ValidatorTestUtil.class);

    /**
     * Assert that validation has failed.
     */
    public static void assertFail(final ValidationReport report, final String... expectedKeys) {
        assertFail(report, true, expectedKeys);
    }

    public static Consumer<ValidationReport> assertFail(final String expectedKey) {
        return r -> assertFail(r, expectedKey);
    }

    /**
     * Assert that validation has failed, and that no context has been attached to the messages.
     */
    public static void assertFailWithoutContext(final ValidationReport report, final String... expectedKeys) {
        assertFail(report, false, expectedKeys);
    }

    /**
     * Assert that validation has failed.
     */
    private static void assertFail(final ValidationReport report, final boolean expectContext, final String... expectedKeys) {
        log.trace(JsonValidationReportFormat.getInstance().apply(report));
        assertThat("Expected validation errors but found none. Enable trace logging for more details.", report.getMessages(), is(not(empty())));

        final List<String> foundKeys = report.getMessages().stream().map(ValidationReport.Message::getKey).toList();

        for (final String key : expectedKeys) {
            assertThat(format("Expected message key '%s' but not found. Found <%s>.", key, foundKeys),
                    foundKeys.contains(key), is(true));
        }

        if (expectContext) {
            report.getMessages().forEach(m -> {
                assertThat("Additional context was expected but none found.", m.getContext().isPresent(), is(true));
                assertThat("Additional context was expected but none found.", m.getContext().get().hasData(), is(true));
            });
        }

    }

    /**
     * Assert that validation has passed.
     */
    public static void assertPass(final ValidationReport report) {
        log.trace(JsonValidationReportFormat.getInstance().apply(report));
        assertTrue(
                report.getMessages().isEmpty() ||
                        report.getMessages().stream().allMatch(m -> m.getLevel() == ValidationReport.Level.IGNORE),
                "Expected no validation errors but found some. Enable trace logging for more details."
        );
    }

    public static Consumer<ValidationReport> assertPass() {
        return ValidatorTestUtil::assertPass;
    }

    /**
     * Returns existing (Optional.isPresent) contexts for messages in {@code report} for message key = {@code key}.
     */
    public static List<Optional<ValidationReport.MessageContext>> getContextsForKey(final ValidationReport report, final String key) {
        return report.getMessages().stream()
                .filter(v -> key.equals(v.getKey()))
                .map(ValidationReport.Message::getContext)
                .toList();
    }

    /**
     * Load a response JSON file with the given name.
     *
     * @param responseName The name of the response to load
     *
     * @return The response JSON as a String, or <code>null</code> if it cannot be loaded
     */
    public static String loadJsonResponse(final String responseName) {
        return loadResource("/responses/" + responseName + ".json");
    }

    /**
     * Load a response XML file with the given name.
     *
     * @param responseName The name of the response to load
     *
     * @return The response XML as a String, or <code>null</code> if it cannot be loaded
     */
    public static String loadXmlResponse(final String responseName) {
        return loadResource("/responses/" + responseName + ".xml");
    }

    /**
     * Load a request JSON file with the given name.
     *
     * @param requestName The name of the request to load
     *
     * @return The response JSON as a String, or <code>null</code> if it cannot be loaded
     */
    public static String loadJsonRequest(final String requestName) {
        return loadResource("/requests/" + requestName + ".json");
    }

    /**
     * Load a request raw file with the given name.
     *
     * @param requestName The name of the request to load
     *
     * @return The response as a String, or <code>null</code> if it cannot be loaded
     */
    public static String loadRawRequest(final String requestName) {
        return loadResource("/requests/" + requestName + ".raw");
    }

    /**
     * Load a request file with the given name and extension.
     *
     * @param requestNameAndExtension The name of the request to load
     *
     * @return The response as a String, or <code>null</code> if it cannot be loaded
     */
    public static String loadRequest(final String requestNameAndExtension) {
        return loadResource("/requests/" + requestNameAndExtension);
    }

    public static String loadResource(final String path) {
        try {
            final InputStream stream = ValidatorTestUtil.class.getResourceAsStream(path);
            final BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            final StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (final Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
