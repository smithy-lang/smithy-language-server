/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.diff;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.ValidatedResult;

public class UrlBaselineProviderTest {

    private static final String BASELINE_JSON = """
            {
              "smithy": "2.0",
              "shapes": {
                "example#Baseline": {
                  "type": "structure",
                  "members": { "id": { "target": "smithy.api#String" } }
                }
              }
            }""";

    @Test
    public void assemblesModelFromJsonAst() {
        ValidatedResult<Model> result = UrlBaselineProvider.assembleFrom(BASELINE_JSON);

        Model model = result.getResult().orElseThrow();
        assertThat(model.expectShape(ShapeId.from("example#Baseline")).getId().toString(),
                is("example#Baseline"));
    }

    @Test
    public void loadBaselineFetchesThenAssembles() {
        UrlBaselineProvider provider =
                new UrlBaselineProvider("https://example.com/baseline.json", uri -> BASELINE_JSON);

        Model model = provider.loadBaseline().getResult().orElseThrow();

        assertThat(model.expectShape(ShapeId.from("example#Baseline")).getId().toString(),
                is("example#Baseline"));
    }

    @Test
    public void fetchesOnceAndMemoizes() {
        AtomicInteger fetches = new AtomicInteger();
        UrlBaselineProvider provider = new UrlBaselineProvider("https://example.com/baseline.json", uri -> {
            fetches.incrementAndGet();
            return BASELINE_JSON;
        });

        ValidatedResult<Model> first = provider.loadBaseline();
        ValidatedResult<Model> second = provider.loadBaseline();

        // Memoized: fetched once, same instance returned across calls.
        assertThat(fetches.get(), is(1));
        assertThat(second, sameInstance(first));
    }

    @Test
    public void throwsWhenFetchFails() {
        UrlBaselineProvider provider = new UrlBaselineProvider("https://example.com/baseline.json", uri -> {
            throw new IOException("connection refused");
        });

        assertThrows(BaselineModelException.class, provider::loadBaseline);
    }

    @Test
    public void throwsOnMalformedUrl() {
        assertThrows(BaselineModelException.class,
                () -> new UrlBaselineProvider("http://bad url with spaces", uri -> BASELINE_JSON));
    }
}
