/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diff;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Pattern;
import software.amazon.smithy.model.SourceException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;

/**
 * The {@code diff} block of a {@code .smithy-project.json} file, configuring in-editor diffing
 *
 * <pre>
 * {
 *   "diff": {
 *     "baseline": { ... },
 *     "enabledEvaluators": ["CompatValidator"],
 *     "disabledEvaluators": []
 *   }
 * }
 * </pre>
 *
 * <p>There are two baseline providers a Maven provider and a URL Provider
 *
 * <pre>
 * { "baseline": { "type": "maven", "coordiante": "com.example:model:1.2.3", transitiveDependencies: true } }
 * { "baseline": { "type": "url", "url": "https://example.com/baseline.json" } }
 * </pre>
 *
 * <p>Absence of the {@code diff} block means the feature is off. {@code enabledEvaluators} and
 * {@code disabledEvaluators} are event-id-prefix lists consumed by {@link DiffEvaluatorFilter};
 * both are optional.
 *
 * @param baseline how to obtain the baseline model
 * @param enabledEvaluators allowlist of evaluator event-id prefixes (empty = allow all)
 * @param disabledEvaluators denylist of evaluator event-id prefixes
 */
public record DiffConfig(
        Baseline baseline,
        List<String> enabledEvaluators,
        List<String> disabledEvaluators
) {

    public DiffConfig {
        enabledEvaluators = List.copyOf(enabledEvaluators);
        disabledEvaluators = List.copyOf(disabledEvaluators);
    }

    /**
     * Parses a {@code diff} block. Throws {@link SourceException} (mapped to a build-file
     * diagnostic by the loader) when required members are missing or malformed.
     *
     * @param node the {@code diff} object node
     * @return the parsed config
     */
    public static DiffConfig fromNode(Node node) {
        ObjectNode objectNode = node.expectObjectNode();
        Baseline baseline = Baseline.fromNode(objectNode.expectObjectMember("baseline"));
        return new DiffConfig(baseline, stringList(objectNode, "enabledEvaluators"),
                stringList(objectNode, "disabledEvaluators"));
    }

    private static List<String> stringList(ObjectNode objectNode, String member) {
        return objectNode.getArrayMember(member)
                .map(arrayNode -> arrayNode.getElementsAs(StringNode.class).stream()
                        .map(StringNode::getValue)
                        .toList())
                .orElse(List.of());
    }

    /**
     * The baseline ("previous") model source. Modeled as a sealed hierarchy so each provider's
     * fields are only present on the variant that uses them and the {@code type} discriminator in
     * the config maps to a concrete subtype: {@link MavenBaseline} resolves a Maven coordinate;
     * {@link UrlBaseline} fetches a Smithy JSON AST model from a URL.
     */
    public sealed interface Baseline permits MavenBaseline, UrlBaseline {
        /**
         * Parses a {@code baseline} block, dispatching on its {@code type} member to the matching
         * variant. Throws {@link SourceException} for an unknown type.
         *
         * @param objectNode the {@code baseline} object node
         * @return the parsed baseline
         */
        static Baseline fromNode(ObjectNode objectNode) {
            String type = objectNode.expectStringMember("type").getValue();
            return switch (type) {
                case MavenBaseline.TYPE -> MavenBaseline.fromNode(objectNode);
                case UrlBaseline.TYPE -> UrlBaseline.fromNode(objectNode);
                default -> throw new SourceException(
                        "Unsupported diff baseline type '" + type + "'; supported types are '"
                                + MavenBaseline.TYPE + "' and '" + UrlBaseline.TYPE + "'",
                        objectNode);
            };
        }
    }

    /**
     * A baseline resolved from a Maven {@code coordinate}.
     *
     * @param coordinate the Maven coordinate of the baseline artifact
     * @param transitiveDependencies whether to also load the coordinate's transitive dependencies
     */
    public record MavenBaseline(String coordinate, boolean transitiveDependencies) implements Baseline {
        public static final String TYPE = "maven";

        private static final String LATEST_VERSION = "LATEST";
        private static final String RELEASE_VERSION = "RELEASE";

        // Mirrors the coordinate shape Maven's resolver (org.eclipse.aether DefaultArtifact, used
        // by Smithy's MavenDependencyResolver) accepts:
        //   <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>
        // i.e. 3 to 5 colon-separated, space-free, non-empty segments with the version last. This
        // rejects version-less (com.example:model) and malformed coordinates at parse time so they
        // surface as a config diagnostic rather than a confusing runtime resolution failure.
        private static final Pattern COORDINATE_PATTERN =
                Pattern.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?:([^: ]+)");

        // The version segment of a coordinate that matched COORDINATE_PATTERN (the version is always
        // the last colon-separated segment).
        private static String versionOf(String coordinate) {
            return coordinate.substring(coordinate.lastIndexOf(':') + 1);
        }

        static MavenBaseline fromNode(ObjectNode objectNode) {
            boolean transitiveDependencies = objectNode.getBooleanMemberOrDefault("transitiveDependencies");
            String coordinate = objectNode.expectStringMember("coordinate").getValue();
            if (!COORDINATE_PATTERN.matcher(coordinate).matches()) {
                throw new SourceException(
                        "Malformed diff baseline coordinate '" + coordinate + "'; expected format is "
                                + "<groupId>:<artifactId>[:<extension>[:<classifier>]]:<version> "
                                + "(a version is required)",
                        objectNode.expectStringMember("coordinate"));
            }
            String version = versionOf(coordinate);
            if (version.equalsIgnoreCase(LATEST_VERSION) || version.equalsIgnoreCase(RELEASE_VERSION)) {
                throw new SourceException(
                        "Unsupported diff baseline version '" + version + "' in coordinate '" + coordinate
                                + "'; the Maven metaversions LATEST/RELEASE are not supported. Use a pinned "
                                + "version (1.4.2), a version range ([1.0,)), or a -SNAPSHOT.",
                        objectNode.expectStringMember("coordinate"));
            }
            return new MavenBaseline(coordinate, transitiveDependencies);
        }
    }

    /**
     * A baseline fetched as a Smithy JSON AST model from an {@code http(s)} URL.
     *
     * @param url the URL to fetch the baseline JSON model from
     */
    public record UrlBaseline(String url) implements Baseline {
        public static final String TYPE = "url";

        static UrlBaseline fromNode(ObjectNode objectNode) {
            String url = objectNode.expectStringMember("url").getValue();
            URI parsed;
            try {
                parsed = new URI(url);
            } catch (URISyntaxException e) {
                throw new SourceException(
                        "Malformed diff baseline url '" + url + "'", objectNode.expectStringMember("url"));
            }
            String scheme = parsed.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new SourceException(
                        "Unsupported diff baseline url scheme in '" + url + "'; expected an http(s) URL",
                        objectNode.expectStringMember("url"));
            }
            return new UrlBaseline(url);
        }
    }
}
