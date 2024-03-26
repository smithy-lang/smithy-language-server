/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.Set;
import java.util.stream.Collectors;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.ValidationEvent;

public final class SmithyMatchers {
    private SmithyMatchers() {}

    public static Matcher<Model> hasShapeWithId(String id) {
        return new CustomTypeSafeMatcher<Model>("a model with the right shape id") {
            @Override
            protected boolean matchesSafely(Model item) {
                return item.getShape(ShapeId.from(id)).isPresent();
            }

            @Override
            public void describeMismatchSafely(Model model, Description description) {
                Set<ShapeId> nonPreludeIds = model.shapes().filter(shape -> !Prelude.isPreludeShape(shape))
                        .map(Shape::toShapeId)
                        .collect(Collectors.toSet());
                description.appendText("had only these non-prelude shapes: " + nonPreludeIds);
            }
        };
    }

    public static Matcher<ValidationEvent> hasMessage(Matcher<String> message) {
        return new CustomTypeSafeMatcher<ValidationEvent>("has matching message") {
            @Override
            protected boolean matchesSafely(ValidationEvent item) {
                return message.matches(item.getMessage());
            }

            @Override
            public void describeMismatchSafely(ValidationEvent event, Description description) {
                description.appendDescriptionOf(message).appendText("was " + event.getMessage());
            }
        };
    }
}
