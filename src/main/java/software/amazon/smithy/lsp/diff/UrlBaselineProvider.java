/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diff;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.validation.ValidatedResult;

/**
 * Baseline provider that fetches the baseline model over HTTP. It issues a {@code GET} to a
 * configured URL and assembles the response body as a Smithy JSON AST model (the format produced
 * by {@code smithy build}'s model serialization / the {@code smithy ast} command).
 *
 * <p>Unlike {@link MavenBaselineProvider}, there is no coordinate resolution: the URL points
 * directly at a single self-contained JSON model. The fetched body is assembled with validation
 * disabled and unknown traits allowed (the baseline was validated when it was produced, and only
 * diff events are ultimately surfaced), mirroring the Maven provider.
 *
 * <p>The assembled baseline is memoized for the lifetime of the provider, so it is fetched once
 * and reused across saves. A URL is treated as a pinned source; the {@code smithy.reloadDiffBaseline}
 * command rebuilds the provider and thus forces a re-fetch.
 *
 * <p>A failure to reach the URL or a non-2xx response throws {@link BaselineModelException}
 * (user-fixable config problem); assembly events from the fetched body are carried in the returned
 * {@link ValidatedResult} rather than thrown.
 */
public final class UrlBaselineProvider implements BaselineProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Seam over the actual network call so the provider can be tested without HTTP. */
    @FunctionalInterface
    interface BodyFetcher {
        String fetch(URI uri) throws IOException, InterruptedException;
    }

    private final URI uri;
    private final BodyFetcher fetcher;

    // Memoizes the assembled baseline so it is fetched once per provider instance. The provider is
    // rebuilt (and this cache dropped) when the configured URL changes or on an explicit reload.
    private ValidatedResult<Model> memoizedResult;

    /**
     * Creates a provider that fetches the baseline from the given URL over real HTTP.
     *
     * @param url the URL to GET the baseline JSON model from
     */
    public UrlBaselineProvider(String url) {
        this(url, UrlBaselineProvider::httpGet);
    }

    /**
     * @param url the URL to GET the baseline JSON model from
     * @param fetcher the body fetcher (the seam for testing without a network)
     */
    UrlBaselineProvider(String url, BodyFetcher fetcher) {
        this.uri = parse(Objects.requireNonNull(url, "url"));
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    private static URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new BaselineModelException("Malformed diff baseline URL '" + url + "'", e);
        }
    }

    @Override
    public synchronized ValidatedResult<Model> loadBaseline() {
        if (memoizedResult == null) {
            memoizedResult = assembleFrom(fetchBody());
        }
        return memoizedResult;
    }

    private String fetchBody() {
        try {
            return fetcher.fetch(uri);
        } catch (IOException e) {
            throw new BaselineModelException("Failed to fetch baseline from URL '" + uri + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Deliberately NOT a BaselineModelException: an interruption (task cancellation,
            // executor shutdown) is transient, not a user-fixable config problem, so it must be
            // handled runtime-quiet (skip the cycle) rather than published as a config error.
            throw new RuntimeException("Interrupted fetching baseline from URL '" + uri + "'", e);
        }
    }

    private static String httpGet(URI uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new BaselineModelException(
                    "Baseline URL '" + uri + "' returned HTTP " + status);
        }
        return response.body();
    }

    /**
     * Assembles a model from a Smithy JSON AST body. Package-private for testing.
     *
     * <p>The {@code .json} source name selects Smithy's JSON AST loader. Assembly events (e.g. a
     * malformed body) are returned in the result, not thrown, mirroring {@link MavenBaselineProvider}.
     */
    static ValidatedResult<Model> assembleFrom(String json) {
        return Model.assembler()
                .putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true)
                .disableValidation()
                .addUnparsedModel("baseline.json", json)
                .assemble();
    }
}
