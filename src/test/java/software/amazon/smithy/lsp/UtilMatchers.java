/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.Optional;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;

public final class UtilMatchers {
    private UtilMatchers() {}

    public static <T> Matcher<Optional<T>> anOptionalOf(Matcher<T> matcher) {
        return new CustomTypeSafeMatcher<Optional<T>>("An optional that is present with value " + matcher.toString()) {
            @Override
            protected boolean matchesSafely(Optional<T> item) {
                return item.isPresent() && matcher.matches(item.get());
            }

            @Override
            public void describeMismatchSafely(Optional<T> item, Description description) {
                if (!item.isPresent()) {
                    description.appendText("was an empty optional");
                } else {
                    matcher.describeMismatch(item.get(), description);
                }
            }
        };
    }
}
