/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp.ext;

import static org.junit.Assert.assertEquals;
import static software.amazon.smithy.model.validation.Severity.WARNING;

import org.eclipse.lsp4j.Diagnostic;
import org.junit.Test;
import software.amazon.smithy.lsp.ProtocolAdapter;
import software.amazon.smithy.model.validation.ValidationEvent;

public class ProtocolAdapterTests {
	@Test
	public void addIdToDiagnostic() {
		final ValidationEvent vEvent = ValidationEvent.builder()
			.message("Oops")
			.id("should-show-up")
			.severity(WARNING)
			.build();
		final Diagnostic actual = ProtocolAdapter.toDiagnostic(vEvent);
		assertEquals("should-show-up: Oops", actual.getMessage());
	}
}
