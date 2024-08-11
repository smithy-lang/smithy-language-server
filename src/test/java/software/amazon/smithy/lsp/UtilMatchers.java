/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Optional;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;

/**
 * Utility hamcrest matchers.
 */
public final class UtilMatchers {
    private UtilMatchers() {}

    public static <T> Matcher<Optional<T>> anOptionalOf(Matcher<T> matcher) {
        return new CustomTypeSafeMatcher<>("An optional that is present with value " + matcher.toString()) {
            @Override
            protected boolean matchesSafely(Optional<T> item) {
                return item.isPresent() && matcher.matches(item.get());
            }

            @Override
            public void describeMismatchSafely(Optional<T> item, Description description) {
                if (item.isEmpty()) {
                    description.appendText("was an empty optional");
                } else {
                    matcher.describeMismatch(item.get(), description);
                }
            }
        };
    }

    public static Matcher<PathMatcher> canMatchPath(Path path) {
        // PathMatcher implementations don't seem to have a nice toString, so this Matcher
        // doesn't print out the PathMatcher that couldn't match, but we could wrap the
        // system default PathMatcher in one that stores the original pattern, if this
        // Matcher becomes too hard to diagnose failures for.
        return new CustomTypeSafeMatcher<PathMatcher>("A matcher that matches " + path) {
            @Override
            protected boolean matchesSafely(PathMatcher item) {
                return item.matches(path);
            }

            @Override
            protected void describeMismatchSafely(PathMatcher item, Description mismatchDescription) {
                mismatchDescription.appendText("did not match");
            }
        };
    }
}
