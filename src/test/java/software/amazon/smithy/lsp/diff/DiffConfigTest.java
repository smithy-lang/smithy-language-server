/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.diff;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.SourceException;
import software.amazon.smithy.model.node.Node;

public class DiffConfigTest {

    @Test
    public void parsesFullConfig() {
        DiffConfig config = DiffConfig.fromNode(Node.parse("""
                {
                  "baseline": { "type": "maven", "coordinate": "com.example:model:1.2.3" },
                  "enabledEvaluators": ["CompatValidator"],
                  "disabledEvaluators": ["RemovedShape"]
                }"""));

        assertThat(config.baseline().type(), is("maven"));
        assertThat(config.baseline().coordinate(), is("com.example:model:1.2.3"));
        assertThat(config.enabledEvaluators(), contains("CompatValidator"));
        assertThat(config.disabledEvaluators(), contains("RemovedShape"));
    }

    @Test
    public void transitiveDependenciesDefaultsToFalse() {
        DiffConfig config = DiffConfig.fromNode(Node.parse("""
                { "baseline": { "type": "maven", "coordinate": "com.example:model:1.2.3" } }"""));

        assertThat(config.baseline().transitiveDependencies(), is(false));
    }

    @Test
    public void parsesTransitiveDependenciesFlag() {
        DiffConfig config = DiffConfig.fromNode(Node.parse("""
                {
                  "baseline": { "type": "maven", "coordinate": "com.example:model:1.2.3", "transitiveDependencies": true }
                }"""));

        assertThat(config.baseline().transitiveDependencies(), is(true));
    }

    @Test
    public void evaluatorListsDefaultToEmpty() {
        DiffConfig config = DiffConfig.fromNode(Node.parse("""
                { "baseline": { "type": "maven", "coordinate": "com.example:model:1.2.3" } }"""));

        assertThat(config.enabledEvaluators(), empty());
        assertThat(config.disabledEvaluators(), empty());
    }

    @Test
    public void missingBaselineThrows() {
        assertThrows(SourceException.class, () -> DiffConfig.fromNode(Node.parse("{}")));
    }

    @Test
    public void missingCoordinateThrows() {
        assertThrows(SourceException.class, () ->
                DiffConfig.fromNode(Node.parse("{ \"baseline\": { \"type\": \"maven\" } }")));
    }

    @Test
    public void latestAndReleaseMetaversionsAreRejected() {
        // Smithy's MavenDependencyResolver rejects the LATEST/RELEASE metaversions at resolve time,
        // so they're rejected at parse time to surface as a config diagnostic rather than a runtime
        // resolution failure.
        SourceException latest = assertThrows(SourceException.class, () -> baseline("com.example:m:LATEST"));
        assertThat(latest.getMessage(), containsString("metaversions LATEST/RELEASE are not supported"));
        assertThrows(SourceException.class, () -> baseline("com.example:m:RELEASE"));
        assertThrows(SourceException.class, () -> baseline("com.example:m:release")); // case-insensitive
    }

    private static DiffConfig.Baseline baseline(String coordinate) {
        return DiffConfig.fromNode(Node.parse(
                "{ \"baseline\": { \"type\": \"maven\", \"coordinate\": \"" + coordinate + "\" } }")).baseline();
    }

    @Test
    public void versionLessCoordinateIsRejected() {
        // A 2-segment coordinate has no version: the old string heuristic would have read the
        // artifactId as the version, but Maven's resolver requires group:artifact:version.
        SourceException ex = assertThrows(SourceException.class, () ->
                DiffConfig.fromNode(Node.parse("""
                        { "baseline": { "type": "maven", "coordinate": "com.example:model" } }""")));
        assertThat(ex.getMessage(), containsString("Malformed diff baseline coordinate 'com.example:model'"));
    }

    @Test
    public void malformedCoordinatesAreRejected() {
        // Trailing colon (empty version), single segment, embedded space, and too many segments
        // are all rejected at parse time so they surface as a config diagnostic.
        assertThrows(SourceException.class, () -> baseline("com.example:model:"));
        assertThrows(SourceException.class, () -> baseline("com.example"));
        assertThrows(SourceException.class, () -> baseline("com.example:model:jar:tests:extra:1.2.3"));
    }

    @Test
    public void acceptsExtensionAndClassifierCoordinateForms() {
        // group:artifact:extension:version and group:artifact:extension:classifier:version are
        // valid Maven coordinate shapes and must parse.
        assertThat(baseline("com.example:model:jar:1.2.3").coordinate(), is("com.example:model:jar:1.2.3"));
        assertThat(baseline("com.example:model:jar:tests:1.2.3").coordinate(),
                is("com.example:model:jar:tests:1.2.3"));
    }

    @Test
    public void unsupportedBaselineTypeThrowsWithHelpfulMessage() {
        SourceException ex = assertThrows(SourceException.class, () ->
                DiffConfig.fromNode(Node.parse("""
                        { "baseline": { "type": "git", "coordinate": "origin/main" } }""")));
        assertThat(ex.getMessage(), containsString("Unsupported diff baseline type 'git'"));
    }

    @Test
    public void parsesUrlBaseline() {
        DiffConfig config = DiffConfig.fromNode(Node.parse("""
                { "baseline": { "type": "url", "url": "https://example.com/baseline.json" } }"""));

        assertThat(config.baseline().type(), is("url"));
        assertThat(config.baseline().url(), is("https://example.com/baseline.json"));
        assertThat(config.baseline().coordinate(), is((String) null));
    }

    @Test
    public void missingUrlThrows() {
        assertThrows(SourceException.class, () ->
                DiffConfig.fromNode(Node.parse("{ \"baseline\": { \"type\": \"url\" } }")));
    }

    @Test
    public void nonHttpUrlSchemeIsRejected() {
        // Only http(s) is fetched; a file:/ftp:/scheme-less URL surfaces as a config diagnostic.
        SourceException ex = assertThrows(SourceException.class, () ->
                DiffConfig.fromNode(Node.parse("""
                        { "baseline": { "type": "url", "url": "ftp://example.com/baseline.json" } }""")));
        assertThat(ex.getMessage(), containsString("expected an http(s) URL"));
        assertThrows(SourceException.class, () ->
                DiffConfig.fromNode(Node.parse("""
                        { "baseline": { "type": "url", "url": "/local/baseline.json" } }""")));
    }
}
