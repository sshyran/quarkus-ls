/*******************************************************************************
* Copyright (c) 2022 Red Hat Inc. and others.
* All rights reserved. This program and the accompanying materials
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v20.html
*
* SPDX-License-Identifier: EPL-2.0
*
* Contributors:
*     Red Hat Inc. - initial API and implementation
*******************************************************************************/
package com.redhat.qute.services.diagnostics;

import static com.redhat.qute.QuteAssert.d;
import static com.redhat.qute.QuteAssert.testDiagnosticsFor;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

/**
 * User tag diagnostics.
 * 
 * @author Angelo ZERR
 *
 */
public class QuteDiagnosticsUserTagTest {

	@Test
	public void undefinedVariableInTemplate() {
		String template = "{name}";
		Diagnostic d = d(0, 1, 0, 5, QuteErrorCode.UndefinedVariable, "`name` cannot be resolved to a variable.",
				DiagnosticSeverity.Warning);
		d.setData(DiagnosticDataFactory.createUndefinedVariableData("name", false));
		testDiagnosticsFor(template, //
				"src/main/resources/templates/user.html", //
				"user", //
				d);
	}

	@Test
	public void undefinedVariableInUserTagTemplate() {
		String template = "{name}";
		testDiagnosticsFor(template, //
				"src/main/resources/templates/tags/user.html", //
				"tags/user");
	}
}
