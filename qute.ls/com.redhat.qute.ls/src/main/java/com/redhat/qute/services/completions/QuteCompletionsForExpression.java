/*******************************************************************************
* Copyright (c) 2021 Red Hat Inc. and others.
* All rights reserved. This program and the accompanying materials
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v20.html
*
* SPDX-License-Identifier: EPL-2.0
*
* Contributors:
*     Red Hat Inc. - initial API and implementation
*******************************************************************************/
package com.redhat.qute.services.completions;

import static com.redhat.qute.parser.template.Section.isCaseSection;
import static com.redhat.qute.project.datamodel.resolvers.ValueResolver.MATCH_NAME_ANY;
import static com.redhat.qute.services.QuteCompletions.EMPTY_FUTURE_COMPLETION;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.redhat.qute.commons.JavaFieldInfo;
import com.redhat.qute.commons.JavaMethodInfo;
import com.redhat.qute.commons.JavaParameterInfo;
import com.redhat.qute.commons.ResolvedJavaTypeInfo;
import com.redhat.qute.ls.commons.snippets.SnippetsBuilder;
import com.redhat.qute.parser.expression.MethodPart;
import com.redhat.qute.parser.expression.NamespacePart;
import com.redhat.qute.parser.expression.Part;
import com.redhat.qute.parser.expression.Parts;
import com.redhat.qute.parser.expression.Parts.PartKind;
import com.redhat.qute.parser.template.CaseOperator;
import com.redhat.qute.parser.template.Expression;
import com.redhat.qute.parser.template.Node;
import com.redhat.qute.parser.template.NodeKind;
import com.redhat.qute.parser.template.Parameter;
import com.redhat.qute.parser.template.ParameterDeclaration;
import com.redhat.qute.parser.template.Section;
import com.redhat.qute.parser.template.SectionKind;
import com.redhat.qute.parser.template.SectionMetadata;
import com.redhat.qute.parser.template.Template;
import com.redhat.qute.parser.template.sections.CaseSection;
import com.redhat.qute.parser.template.sections.CaseSection.CompletionCaseResult;
import com.redhat.qute.parser.template.sections.LoopSection;
import com.redhat.qute.parser.template.sections.WhenSection;
import com.redhat.qute.parser.template.sections.WithSection;
import com.redhat.qute.project.datamodel.ExtendedDataModelParameter;
import com.redhat.qute.project.datamodel.ExtendedDataModelTemplate;
import com.redhat.qute.project.datamodel.JavaDataModelCache;
import com.redhat.qute.project.datamodel.resolvers.FieldValueResolver;
import com.redhat.qute.project.datamodel.resolvers.MethodValueResolver;
import com.redhat.qute.project.datamodel.resolvers.ValueResolver;
import com.redhat.qute.services.nativemode.JavaTypeAccessibiltyRule;
import com.redhat.qute.services.nativemode.JavaTypeFilter;
import com.redhat.qute.services.nativemode.JavaTypeFilter.JavaMemberAccessibility;
import com.redhat.qute.settings.QuteCompletionSettings;
import com.redhat.qute.settings.QuteFormattingSettings;
import com.redhat.qute.settings.QuteNativeSettings;
import com.redhat.qute.utils.DocumentationUtils;
import com.redhat.qute.utils.QutePositionUtility;
import com.redhat.qute.utils.StringUtils;
import com.redhat.qute.utils.UserTagUtils;

/**
 * Qute completion inside Qute expression.
 *
 * @author Angelo ZERR
 *
 */
public class QuteCompletionsForExpression {

	private final QuteCompletionForTagSection completionForTagSection;

	private final JavaDataModelCache javaCache;

	public QuteCompletionsForExpression(QuteCompletionForTagSection completionForTagSection,
			JavaDataModelCache javaCache) {
		this.completionForTagSection = completionForTagSection;
		this.javaCache = javaCache;
	}

	/**
	 * Returns the completion result of the given offset inside the given
	 * expression.
	 *
	 * @param completionRequest    the completion request
	 * @param expression           the expression where the completion has been
	 *                             triggered and null otherwise.
	 * @param nodeExpression       the node expression where the completion has been
	 *                             triggered and null otherwise.
	 * @param template             the owner template.
	 * @param offset               the offset where the completion has been
	 *                             triggered.
	 * @param completionSettings   the completion settings.
	 * @param formattingSettings   the formatting settings.
	 * @param nativeImagesSettings the native images settings.
	 * @param cancelChecker        the cancel checker.
	 *
	 * @return the completion result as future.
	 */
	public CompletableFuture<CompletionList> doCompleteExpression(CompletionRequest completionRequest,
			Expression expression, Node nodeExpression, Template template, int offset,
			QuteCompletionSettings completionSettings, QuteFormattingSettings formattingSettings,
			QuteNativeSettings nativeImagesSettings, CancelChecker cancelChecker) {
		if (nodeExpression == null) {
			// ex : { | }
			return doCompleteExpressionForObjectPart(completionRequest, expression, null, null, offset, template,
					completionSettings, formattingSettings, nativeImagesSettings, cancelChecker);
		} else if (expression == null) {
			// ex : {|
			return doCompleteExpressionForObjectPart(completionRequest, null, null, nodeExpression, offset, template,
					completionSettings, formattingSettings, nativeImagesSettings, cancelChecker);
		}

		if (nodeExpression.getKind() == NodeKind.ExpressionPart) {
			Part part = (Part) nodeExpression;
			switch (part.getPartKind()) {
			case Object:
				// ex : { ite|m }
				return doCompleteExpressionForObjectPart(null, expression, part.getNamespace(), part, offset, template,
						completionSettings, formattingSettings, nativeImagesSettings, cancelChecker);
			case Property: {
				// ex : { item.n| }
				// ex : { item.n|ame }
				Parts parts = part.getParent();
				return doCompleteExpressionForMemberPart(part, parts, template, false, completionSettings,
						formattingSettings, nativeImagesSettings, cancelChecker);
			}
			case Method: {
				// ex : { item.getN|ame() }
				// ex : { item.getName(|) }
				MethodPart methodPart = (MethodPart) part;
				if (methodPart.isInParameters(offset)) {
					// ex : { item.getName(|) }
					return doCompleteExpressionForObjectPart(null, expression, null, null, offset, template,
							completionSettings, formattingSettings, nativeImagesSettings, cancelChecker);
				}
				// ex : { item.getN|ame() }
				Parts parts = part.getParent();
				return doCompleteExpressionForMemberPart(part, parts, template, methodPart.isInfixNotation(),
						completionSettings, formattingSettings, nativeImagesSettings, cancelChecker);
			}
			default:
				break;
			}
			return EMPTY_FUTURE_COMPLETION;
		}

		if (nodeExpression.getKind() == NodeKind.ExpressionParts) {
			char previous = template.getText().charAt(offset - 1);
			switch (previous) {
			case ':': {
				// ex : { data:| }
				// ex : { data:|name }
				Parts parts = (Parts) nodeExpression;
				Part part = parts.getPartAt(offset + 1);
				return doCompleteExpressionForObjectPart(null, expression, parts.getNamespace(), part, offset, template,
						completionSettings, formattingSettings, nativeImagesSettings, cancelChecker);
			}
			case '.': {
				// ex : { item.| }
				// ex : { item.|name }
				// ex : { item.|getName() }
				Parts parts = (Parts) nodeExpression;
				Part part = parts.getPartAt(offset + 1);
				return doCompleteExpressionForMemberPart(part, parts, template, false, completionSettings,
						formattingSettings, nativeImagesSettings, cancelChecker);
			}
			case ' ': {
				// Infix notation
				// ex : { item | }
				// ex : { item |name }
				// ex : { item ?: |name }
				Parts parts = (Parts) nodeExpression;
				Part part = parts.getPartAt(offset + 1);
				Part previousPart = parts.getPreviousPart(part);
				if (previousPart != null && previousPart.getPartKind() == PartKind.Method
						&& ((MethodPart) previousPart).isOperator()) {
					// ex : { item ?: |name }
					return doCompleteExpressionForObjectPart(null, expression, parts.getNamespace(), part, offset,
							template, completionSettings, formattingSettings, nativeImagesSettings, cancelChecker);
				}
				// ex : { item | }
				// ex : { item |name }
				return doCompleteExpressionForMemberPart(part, parts, template, true, completionSettings,
						formattingSettings, nativeImagesSettings, cancelChecker);
			}
			}
		}
		return EMPTY_FUTURE_COMPLETION;
	}

	/**
	 * Returns the completion result for Java fields, methods of the Java type
	 * class, interface of the given part <code>part</code>
	 *
	 * @param part                 the part.
	 * @param parts                the owner parts.
	 * @param template             the owner template.
	 * @param infixNotation        true if the completion generates completion items
	 *                             for infix notation (with spaces) and false
	 *                             otherwise.
	 * @param completionSettings   the completion settings.
	 * @param formattingSettings   the formatting settings.
	 * @param nativeImagesSettings the native image settings.
	 * @param cancelChecker        the cancel checker
	 *
	 * @return the completion list.
	 */
	private CompletableFuture<CompletionList> doCompleteExpressionForMemberPart(Part part, Parts parts,
			Template template, boolean infixNotation, QuteCompletionSettings completionSettings,
			QuteFormattingSettings formattingSettings, QuteNativeSettings nativeImagesSettings,
			CancelChecker cancelChecker) {
		int start = part != null ? part.getStart() : parts.getEnd();
		int end = part != null ? part.getEnd() : parts.getEnd();
		String projectUri = template.getProjectUri();
		Part previousPart = parts.getPreviousPart(part);
		return javaCache.resolveJavaType(previousPart, projectUri) //
				.thenCompose(resolvedType -> {
					cancelChecker.checkCanceled();

					if (resolvedType == null) {
						return EMPTY_FUTURE_COMPLETION;
					}

					// Completion for member of the given Java class
					// ex : org.acme.Item
					CompletionList list = doCompleteForJavaTypeMembers(resolvedType, start, end, template,
							infixNotation, completionSettings, formattingSettings, nativeImagesSettings);
					return CompletableFuture.completedFuture(list);
				});

	}

	/**
	 * Returns the completion result for Java fields, methods of the given Java type
	 * class, interface <code>resolvedType</code>
	 *
	 * @param baseType             the Java class, interface.
	 * @param start                the part start index to replace.
	 * @param end                  the part end index to replace.
	 * @param template             the owner Qute template.
	 * @param infixNotation        true if the completion generates completion items
	 *                             for infix notation (with spaces) and false
	 *                             otherwise.
	 * @param completionSettings   the completion settings.
	 * @param formattingSettings   the formatting settings.
	 * @param nativeImagesSettings the native image settings.
	 *
	 * @return the completion list.
	 */
	private CompletionList doCompleteForJavaTypeMembers(ResolvedJavaTypeInfo baseType, int start, int end,
			Template template, boolean infixNotation, QuteCompletionSettings completionSettings,
			QuteFormattingSettings formattingSettings, QuteNativeSettings nativeImagesSettings) {
		Set<CompletionItem> completionItems = new HashSet<>();
		Range range = QutePositionUtility.createRange(start, end, template);
		String projectUri = template.getProjectUri();

		Set<String> existingProperties = new HashSet<>();
		Set<String> existingMethodSignatures = new HashSet<>();
		JavaTypeFilter filter = javaCache.getJavaTypeFilter(projectUri, nativeImagesSettings);
		JavaTypeAccessibiltyRule javaTypeAccessibility = !filter.isInNativeMode()
				? JavaTypeAccessibiltyRule.ALLOWED_WITHOUT_RESTRICTION
				: filter.getJavaTypeAccessibility(baseType, template.getJavaTypesSupportedInNativeMode());

		if (javaTypeAccessibility != null) {

			// Some fields and methods from Java reflection must be shown.
			// - in NO native images mode : all fields and methods must be shown
			// - in native images mode : some fields and methods must be shown according the
			// @TemplateData / @RegisterForReflection annotations.

			if (!infixNotation) {
				// Completion for Java fields
				fillCompletionFields(baseType, javaTypeAccessibility, filter, range, projectUri, existingProperties,
						completionItems);
			}

			// Completion for Java methods
			fillCompletionMethods(baseType, javaTypeAccessibility, filter, range, projectUri, infixNotation,
					completionSettings, formattingSettings, existingProperties, existingMethodSignatures,
					completionItems);
		}

		// Completion for virtual methods (from value resolvers)

		// - Static value resolvers (orEmpty, etc)
		// - Dynamic value resolvers (from @TemplateExtension)
		List<MethodValueResolver> resolvers = javaCache.getResolversFor(baseType, projectUri);
		for (MethodValueResolver method : resolvers) {
			if (method.isValidName() && isValidInfixNotation(method, infixNotation)) {
				fillCompletionMethod(method, javaTypeAccessibility, null, range, infixNotation, completionSettings,
						formattingSettings, completionItems);
			}
		}
		CompletionList list = new CompletionList();
		list.setItems(completionItems.stream().collect(Collectors.toList()));
		return list;
	}

	/**
	 * Make completion items for the fields of the given Java type
	 * <code>resolvedType</code>, then add them to <code>completionItems</code>.
	 *
	 * @param baseType              the Java type class, interface.
	 * @param javaTypeAccessibility the Java type accessibility.
	 * @param filter                the Java type filter.
	 * @param range                 the range.
	 * @param projectUri            the project Uri
	 * @param existingProperties    the existing properties and method, field
	 *                              signature.
	 * @param completionItems       the set of completion items to add to
	 */
	private void fillCompletionFields(ResolvedJavaTypeInfo baseType, JavaTypeAccessibiltyRule javaTypeAccessibility,
			JavaTypeFilter filter, Range range, String projectUri, Set<String> existingProperties,
			Set<CompletionItem> completionItems) {
		fillCompletionFields(baseType, javaTypeAccessibility, filter, range, projectUri, existingProperties,
				completionItems, new HashSet<>());
	}

	/**
	 * Make completion items for the fields of the given Java type
	 * <code>resolvedType</code>, then add them to <code>completionItems</code>.
	 *
	 * @param baseType              the Java type class, interface.
	 * @param javaTypeAccessibility the Java type accessibility.
	 * @param filter                the Java type filter.
	 * @param range                 the range.
	 * @param projectUri            the project Uri
	 * @param existingProperties    the existing properties and method, field
	 *                              signature.
	 * @param completionItems       the set of completion items to add to
	 * @param visited               the list of types that have already been visited
	 */
	private void fillCompletionFields(ResolvedJavaTypeInfo baseType, JavaTypeAccessibiltyRule javaTypeAccessibility,
			JavaTypeFilter filter, Range range, String projectUri, Set<String> existingProperties,
			Set<CompletionItem> completionItems, Set<ResolvedJavaTypeInfo> visited) {
		if (visited.contains(baseType)) {
			return;
		}
		visited.add(baseType);
		// Fill completion with Java type fields.
		for (JavaFieldInfo field : baseType.getFields()) {
			String fieldName = field.getName();
			// It is necessary to check if the name of the given field has already been
			// added in the completion
			// list to avoid duplication of input fields.
			// Ex: to avoid duplicate of foo fields in this case
			// class A extends B {String foo;}
			// class B {String foo;}
			if (!existingProperties.contains(fieldName)) {
				fillCompletionField(field, javaTypeAccessibility, filter, range, completionItems);
				existingProperties.add(fieldName);
			}
		}
		if (!isIgnoreSuperclasses(baseType, javaTypeAccessibility, filter)) {
			// Fill completion with extended Java type fields.
			List<String> extendedTypes = baseType.getExtendedTypes();
			if (extendedTypes != null) {
				for (String extendedType : extendedTypes) {
					ResolvedJavaTypeInfo resolvedExtendedType = javaCache.resolveJavaType(extendedType, projectUri)
							.getNow(null);
					if (resolvedExtendedType != null) {
						fillCompletionFields(resolvedExtendedType, javaTypeAccessibility, filter, range, projectUri,
								existingProperties, completionItems, visited);
					}
				}
			}
		}
	}

	private static void fillCompletionField(JavaFieldInfo field, JavaTypeAccessibiltyRule javaTypeAccessibility,
			JavaTypeFilter filter, Range range, Set<CompletionItem> completionItems) {
		if (!JavaTypeAccessibiltyRule.ALLOWED_WITHOUT_RESTRICTION.equals(javaTypeAccessibility) && filter != null
				&& filter.getJavaMemberAccessibility(field, javaTypeAccessibility) != JavaMemberAccessibility.ALLOWED) {
			return;
		}
		fillCompletionField(field, null, false, range, completionItems);
	}

	private static CompletionItem fillCompletionField(JavaFieldInfo field, String namespace,
			boolean useNamespaceInTextEdit, Range range, Set<CompletionItem> completionItems) {
		String label = namespace != null ? namespace + ':' + field.getSimpleSignature() : field.getSimpleSignature();
		String insertText = useNamespaceInTextEdit && namespace != null ? namespace + ':' + field.getName()
				: field.getName();
		CompletionItem item = new CompletionItem();
		item.setLabel(label);
		item.setFilterText(insertText);
		item.setKind(CompletionItemKind.Field);
		TextEdit textEdit = new TextEdit();
		textEdit.setRange(range);
		textEdit.setNewText(insertText);
		item.setTextEdit(Either.forLeft(textEdit));
		completionItems.add(item);
		return item;
	}

	private void fillCompletionMethods(ResolvedJavaTypeInfo baseType, JavaTypeAccessibiltyRule javaTypeAccessibility,
			JavaTypeFilter filter, Range range, String projectUri, boolean infixNotation,
			QuteCompletionSettings completionSettings, QuteFormattingSettings formattingSettings,
			Set<String> existingProperties, Set<String> existingMethodSignatures, Set<CompletionItem> completionItems) {
		fillCompletionMethods(baseType, javaTypeAccessibility, filter, range, projectUri, infixNotation,
				completionSettings, formattingSettings, existingProperties, existingMethodSignatures, completionItems,
				new HashSet<>());
	}

	/**
	 * Make completion items for the methods of the given Java type
	 * <code>resolvedType</code> and add them to <code>completionItems</code>.
	 *
	 * @param baseType              the Java type class, interface.
	 * @param javaTypeAccessibility the Java type accessibility.
	 * @param filter                the Java type filter.
	 * @param range                 the range.
	 * @param projectUri            the project Uri
	 * @param infixNotation         true if the completion generates completion
	 *                              items for infix notation (with spaces) and false
	 *                              otherwise.
	 * @param completionSettings    the completion settings
	 * @param formattingSettings    the formatting settings.
	 * @param existingProperties    the existing properties and method, field
	 *                              signature.
	 * @param completionItems       the set of completion items to add to
	 * @param visited               the set of types that have already been visited
	 */
	private void fillCompletionMethods(ResolvedJavaTypeInfo baseType, JavaTypeAccessibiltyRule javaTypeAccessibility,
			JavaTypeFilter filter, Range range, String projectUri, boolean infixNotation,
			QuteCompletionSettings completionSettings, QuteFormattingSettings formattingSettings,
			Set<String> existingProperties, Set<String> existingMethodSignatures, Set<CompletionItem> completionItems,
			Set<ResolvedJavaTypeInfo> visited) {

		if (visited.contains(baseType)) {
			return;
		}
		visited.add(baseType);

		for (JavaMethodInfo method : baseType.getMethods()) {
			if (isValidInfixNotation(method, infixNotation)) {
				String methodSignature = method.getSignature();
				if (!existingMethodSignatures.contains(methodSignature)) {
					existingMethodSignatures.add(methodSignature);
					String property = method.getGetterName();
					if (property != null && !existingProperties.contains(property)) {
						// It's a getter method, create a completion item for simple property (value)
						// from the method name (getValue)
						CompletionItem item = new CompletionItem();
						item.setLabel(property + " : " + method.getJavaElementSimpleType());
						item.setFilterText(property);
						item.setKind(CompletionItemKind.Property);
						TextEdit textEdit = new TextEdit();
						textEdit.setRange(range);
						textEdit.setNewText(property);
						item.setTextEdit(Either.forLeft(textEdit));
						completionItems.add(item);
					}

					// Completion for method name (getValue)
					fillCompletionMethod(method, javaTypeAccessibility, filter, range, infixNotation,
							completionSettings, formattingSettings, completionItems);
				}
			}
		}

		if (!isIgnoreSuperclasses(baseType, javaTypeAccessibility, filter)) {
			List<String> extendedTypes = baseType.getExtendedTypes();
			if (extendedTypes != null) {
				for (String extendedType : extendedTypes) {
					ResolvedJavaTypeInfo resolvedExtendedType = javaCache.resolveJavaType(extendedType, projectUri)
							.getNow(null);
					if (resolvedExtendedType != null) {
						fillCompletionMethods(resolvedExtendedType, javaTypeAccessibility, filter, range, projectUri,
								infixNotation, completionSettings, formattingSettings, existingProperties,
								existingMethodSignatures, completionItems, visited);
					}
				}
			}
		}
	}

	/**
	 * Make completion items for relevant case operators and add them to
	 * <code>completionItems</code>.
	 *
	 * @param caseSection        the Case Section.
	 * @param onlyMulti          if true, only operators that work with multi case
	 *                           matches will be suggested for completion
	 * @param range              the range.
	 * @param existingParameters the existing parameters.
	 * @param completionItems    the set of completion items to update
	 * @param canSupportMarkdown whether the client supports rendering markdown
	 */
	private void fillCaseOperators(CaseSection caseSection, boolean onlyMulti, Range range,
			Set<String> existingParameters, Set<CompletionItem> completionItems, boolean canSupportMarkdown) {
		for (CaseOperator operator : caseSection.getAllowedOperators()) {
			if (!onlyMulti || operator.isMulti()) {
				CompletionItem item = new CompletionItem();
				item.setLabel(operator.getName() + " : Operator");
				item.setFilterText(operator.getName());
				item.setKind(CompletionItemKind.Operator);
				item.setDocumentation(DocumentationUtils.getDocumentation(operator, canSupportMarkdown));
				TextEdit textEdit = new TextEdit();
				textEdit.setRange(range);
				textEdit.setNewText(operator.getName());
				item.setTextEdit(Either.forLeft(textEdit));
				completionItems.add(item);
			}
		}
	}

	private static boolean isIgnoreSuperclasses(ResolvedJavaTypeInfo baseType,
			JavaTypeAccessibiltyRule javaTypeAccessibility, JavaTypeFilter filter) {
		return filter != null && filter.isIgnoreSuperclasses(baseType, javaTypeAccessibility);
	}

	private static boolean isValidInfixNotation(JavaMethodInfo method, boolean infixNotation) {
		if (!infixNotation) {
			return true;
		}
		int nbParameters = method.getParameterslength();
		return nbParameters == 1;
	}

	private static void fillCompletionMethod(JavaMethodInfo method, JavaTypeAccessibiltyRule javaTypeAccessibility,
			JavaTypeFilter filter, Range range, boolean infixNotation, QuteCompletionSettings completionSettings,
			QuteFormattingSettings formattingSettings, Set<CompletionItem> completionItems) {
		if (!JavaTypeAccessibiltyRule.ALLOWED_WITHOUT_RESTRICTION.equals(javaTypeAccessibility) && filter != null
				&& filter.getJavaMemberAccessibility(method,
						javaTypeAccessibility) != JavaMemberAccessibility.ALLOWED) {
			return;
		}
		fillCompletionMethod(method, null, false, range, infixNotation, completionSettings, formattingSettings,
				completionItems);
	}

	private static CompletionItem fillCompletionMethod(JavaMethodInfo method, String namespace,
			boolean useNamespaceInTextEdit, Range range, boolean infixNotation,
			QuteCompletionSettings completionSettings, QuteFormattingSettings formattingSettings,
			Set<CompletionItem> completionItems) {
		String label = namespace != null ? namespace + ':' + method.getSimpleSignature() : method.getSimpleSignature();
		String filterText = namespace != null ? namespace + ':' + method.getMethodName() : method.getMethodName();
		CompletionItem item = new CompletionItem();
		item.setLabel(label);
		item.setFilterText(filterText);
		item.setKind(method.isVirtual() ? CompletionItemKind.Function : CompletionItemKind.Method);
		item.setInsertTextFormat(completionSettings.isCompletionSnippetsSupported() ? InsertTextFormat.Snippet
				: InsertTextFormat.PlainText);
		TextEdit textEdit = new TextEdit();
		textEdit.setRange(range);
		String insertText = createMethodSnippet(method, infixNotation, completionSettings, formattingSettings);
		if (useNamespaceInTextEdit && namespace != null) {
			insertText = namespace + ':' + insertText;
		}
		textEdit.setNewText(insertText);
		item.setTextEdit(Either.forLeft(textEdit));
		completionItems.add(item);
		return item;
	}

	private static String createMethodSnippet(JavaMethodInfo method, boolean infixNotation,
			QuteCompletionSettings completionSettings, QuteFormattingSettings formattingSettings) {
		boolean snippetsSupported = completionSettings.isCompletionSnippetsSupported();
		String methodName = method.getMethodName();
		StringBuilder snippet = new StringBuilder();
		if (MATCH_NAME_ANY.equals(methodName)) {
			// config:*
			JavaParameterInfo firstParameter = method.getParameterAt(0);
			String parameterName = firstParameter != null ? firstParameter.getName() : methodName;
			if (snippetsSupported) {
				SnippetsBuilder.placeholders(1, parameterName, snippet);
			} else {
				snippet.append(parameterName);
			}
			if (snippetsSupported) {
				SnippetsBuilder.tabstops(0, snippet);
			}
		} else {
			snippet.append(methodName);
			if (method.hasParameters()) {
				int start = 0;
				if (method.isVirtual() && ((ValueResolver) method).getNamespace() == null) {
					start++;
				}

				if (!infixNotation) {
					snippet.append('(');
				} else {
					snippet.append(' ');
				}
				for (int i = start; i < method.getParameters().size(); i++) {
					if (i > start) {
						snippet.append(", ");
					}
					JavaParameterInfo parameter = method.getParameterAt(i);
					if (snippetsSupported) {
						SnippetsBuilder.placeholders(i - start + 1, parameter.getName(), snippet);
					} else {
						snippet.append(parameter.getName());
					}
				}
				if (!infixNotation) {
					snippet.append(')');
				}
				if (snippetsSupported) {
					SnippetsBuilder.tabstops(0, snippet);
				}
			}
		}
		return snippet.toString();
	}

	private CompletableFuture<CompletionList> doCompleteExpressionForObjectPart(CompletionRequest completionRequest,
			Expression expression, String namespace, Node part, int offset, Template template,
			QuteCompletionSettings completionSettings, QuteFormattingSettings formattingSettings,
			QuteNativeSettings nativeImagesSettings, CancelChecker cancelChecker) {
		// Completion for root object
		int partStart = part != null && part.getKind() != NodeKind.Text ? part.getStart() : offset;
		int partEnd = part != null && part.getKind() != NodeKind.Text ? part.getEnd() : offset;
		Range range = QutePositionUtility.createRange(partStart, partEnd, template);
		boolean isCaseSection = isInCaseSection(expression);
		Set<CompletionItem> completionItems = new HashSet<>();

		// Don't complete if in #is or #case section
		if (!isCaseSection) {
			// Completion with namespace resolver
			// {data:item}
			// {inject:bean}
			doCompleteNamespaceResolvers(namespace, template, range, completionSettings, formattingSettings,
					completionItems);
		}

		if (namespace == null) {
			// Completion is triggered before the namespace
			if (!isCaseSection) {
				// Collect global variable
				doCompleteExpressionForObjectPartWithGlobalVariables(template, range, completionItems);
				// Collect alias declared from parameter declaration
				doCompleteExpressionForObjectPartWithParameterAlias(template, range, completionItems);
				// Collect parameters from CheckedTemplate method parameters
				doCompleteExpressionForObjectPartWithCheckedTemplate(template, range, completionItems);
			}
			// Collect declared model inside section, let, etc
			Set<String> existingVars = new HashSet<>();
			doCompleteExpressionForObjectPartWithParentNodes(part, expression != null ? expression : part, range,
					offset, template, existingVars, completionSettings, formattingSettings, nativeImagesSettings,
					completionItems);

			// Section tag
			if (completionRequest != null) {
				char previous = template.getText().charAt(offset - 1);
				if (previous == '#') {
					completionForTagSection.doCompleteTagSection(completionRequest, "#", completionSettings,
							formattingSettings, cancelChecker, completionItems);
				}
			}

			if (UserTagUtils.isUserTag(template)) {
				// provide completion for 'it' and 'nested-content'
				Collection<SectionMetadata> metadatas = UserTagUtils.getSpecialKeys();
				for (SectionMetadata metadata : metadatas) {
					String name = metadata.getName();
					if (!existingVars.contains(name)) {
						existingVars.add(name);
						CompletionItem item = new CompletionItem();
						item.setLabel(name);
						item.setKind(CompletionItemKind.Keyword);
						// Display metadata section (ex : count for #each) after declared objects
						item.setSortText("Za" + name);
						TextEdit textEdit = new TextEdit(range, name);
						item.setTextEdit(Either.forLeft(textEdit));
						item.setDetail(metadata.getDescription());
						completionItems.add(item);
					}
				}
			}
		}

		CompletionList list = new CompletionList();
		list.setItems(completionItems.stream().collect(Collectors.toList()));
		return CompletableFuture.completedFuture(list);
	}

	private static boolean isInCaseSection(Expression expression) {
		return (expression != null && expression.getOwnerSection() != null
				&& isCaseSection(expression.getOwnerSection()));
	}

	private void doCompleteExpressionForObjectPartWithGlobalVariables(Template template, Range range,
			Set<CompletionItem> completionItems) {
		List<ValueResolver> globalVariables = javaCache.getGlobalVariables(template.getProjectUri());
		if (globalVariables != null) {
			for (ValueResolver globalVariable : globalVariables) {
				String name = globalVariable.getNamed();
				if (name == null) {
					name = globalVariable.getName();
				}
				CompletionItem item = new CompletionItem();
				item.setLabel(name);
				item.setKind(getCompletionKind(globalVariable));
				TextEdit textEdit = new TextEdit(range, name);
				item.setTextEdit(Either.forLeft(textEdit));
				completionItems.add(item);
			}
		}
	}

	private static CompletionItemKind getCompletionKind(ValueResolver globalVariable) {
		switch (globalVariable.getJavaElementKind()) {
		case FIELD:
			return CompletionItemKind.Field;
		case METHOD:
			return CompletionItemKind.Method;
		case TYPE:
			return CompletionItemKind.Class;
		case PARAMETER:
			return CompletionItemKind.TypeParameter;
		}
		return CompletionItemKind.Class;
	}

	public void doCompleteNamespaceResolvers(String namespace, Template template, Range range,
			QuteCompletionSettings completionSettings, QuteFormattingSettings formattingSettings,
			Set<CompletionItem> completionItems) {
		if (NamespacePart.DATA_NAMESPACE.equals(namespace)) {
			// {data:|}
			// Collect alias declared from parameter declaration
			doCompleteExpressionForObjectPartWithParameterAlias(template, range, completionItems);
			// Collect parameters from CheckedTemplate method parameters
			doCompleteExpressionForObjectPartWithCheckedTemplate(template, range, completionItems);
			return;
		}

		// {inject:|}
		// {|inject:}
		// {config:|}
		// {|config:}
		Set<String> existingResovers = new HashSet<>();
		List<ValueResolver> namespaceResolvers = javaCache.getNamespaceResolvers(namespace, template.getProjectUri());
		for (ValueResolver resolver : namespaceResolvers) {
			boolean useNamespaceInTextEdit = namespace == null;
			String named = resolver.getNamed();
			if (named != null) {
				// @Named("user")
				// User getUser();
				String label = useNamespaceInTextEdit ? resolver.getNamespace() + ':' + named : named;
				if (!existingResovers.contains(label)) {
					existingResovers.add(label);
					CompletionItem item = new CompletionItem();
					item.setLabel(label);
					item.setFilterText(label);
					item.setKind(CompletionItemKind.Field);
					TextEdit textEdit = new TextEdit();
					textEdit.setRange(range);
					textEdit.setNewText(label);
					item.setTextEdit(Either.forLeft(textEdit));
					item.setSortText("Zb" + item.getLabel());
					completionItems.add(item);
				}
			} else {
				switch (resolver.getJavaElementKind()) {
				case METHOD: {
					MethodValueResolver method = (MethodValueResolver) resolver;
					CompletionItem item = fillCompletionMethod(method, method.getNamespace(), useNamespaceInTextEdit,
							range, false, completionSettings, formattingSettings, completionItems);
					item.setKind(CompletionItemKind.Function);
					// Display namespace resolvers (ex : config:getConfigProperty(...)) after
					// declared objects
					item.setSortText("Zc" + item.getLabel());
					break;
				}
				case FIELD: {
					FieldValueResolver field = (FieldValueResolver) resolver;
					CompletionItem item = fillCompletionField(field, field.getNamespace(), namespace == null, range,
							completionItems);
					item.setKind(CompletionItemKind.Field);
					// Display namespace resolvers (ex : inject:bean) after
					// declared objects
					item.setSortText("Zb" + item.getLabel());
					break;
				}
				default:
				}
			}
		}
	}

	private CompletableFuture<Void> doCompleteExpressionForObjectPartWithParentNodes(Node part, Node node, Range range,
			int offset, Template template, Set<String> existingVars, QuteCompletionSettings completionSettings,
			QuteFormattingSettings formattingSettings, QuteNativeSettings nativeImagesSettings,
			Set<CompletionItem> completionItems) {
		Section parentSection = node != null ? node.getParentSection() : null;
		if (parentSection == null) {
			return CompletableFuture.completedFuture(null);
		}
		if (parentSection.getKind() == NodeKind.Section) {
			boolean collect = true;
			if (parentSection.getSectionKind() == SectionKind.FOR
					|| parentSection.getSectionKind() == SectionKind.EACH) {
				LoopSection iterableSection = ((LoopSection) parentSection);
				if (iterableSection.isInElseBlock(offset)) {
					// Completion is triggered after a #else inside a #for, we don't provide
					// completion for metadata or aliases
					collect = false;
				}
			}

			if (collect) {

				// 1) Completion for metadata section
				List<SectionMetadata> metadatas = parentSection.getMetadata();
				for (SectionMetadata metadata : metadatas) {
					String name = metadata.getName();
					if (!existingVars.contains(name)) {
						existingVars.add(name);
						CompletionItem item = new CompletionItem();
						item.setLabel(name);
						item.setKind(CompletionItemKind.Keyword);
						// Display metadata section (ex : count for #each) after declared objects
						item.setSortText("Za" + name);
						TextEdit textEdit = new TextEdit(range, name);
						item.setTextEdit(Either.forLeft(textEdit));
						item.setDetail(metadata.getDescription());
						completionItems.add(item);
					}
				}

				// 2) Completion for aliases section
				switch (parentSection.getSectionKind()) {
				case EACH:
				case FOR:
					LoopSection iterableSection = ((LoopSection) parentSection);
					// Completion for iterable section like #each, #for
					String alias = iterableSection.getAlias();
					if (!StringUtils.isEmpty(alias)) {
						if (!existingVars.contains(alias)) {
							existingVars.add(alias);
							CompletionItem item = new CompletionItem();
							item.setLabel(alias);
							item.setKind(CompletionItemKind.Reference);
							TextEdit textEdit = new TextEdit(range, alias);
							item.setTextEdit(Either.forLeft(textEdit));
							completionItems.add(item);
						}
					}
					break;
				case LET:
				case SET: {
					// completion for parameters coming from #let, #set
					List<Parameter> parameters = parentSection.getParameters();
					if (parameters != null) {
						for (Parameter parameter : parameters) {
							String parameterName = parameter.getName();
							if (!existingVars.contains(parameterName)) {
								existingVars.add(parameterName);
								CompletionItem item = new CompletionItem();
								item.setLabel(parameterName);
								item.setKind(CompletionItemKind.Reference);
								TextEdit textEdit = new TextEdit(range, parameterName);
								item.setTextEdit(Either.forLeft(textEdit));
								completionItems.add(item);
							}
						}
					}
					break;
				}
				case IF: {
					// completion for parameters coming from #if
					List<Parameter> parameters = parentSection.getParameters();
					if (parameters != null) {
						for (Parameter parameter : parameters) {
							if (parameter.isOptional()) {
								// {#if foo??}
								String parameterName = parameter.getName();
								if (!existingVars.contains(parameterName)) {
									existingVars.add(parameterName);
									CompletionItem item = new CompletionItem();
									item.setLabel(parameterName);
									item.setKind(CompletionItemKind.Reference);
									TextEdit textEdit = new TextEdit(range, parameterName);
									item.setTextEdit(Either.forLeft(textEdit));
									completionItems.add(item);
								}
							}
						}
					}
					break;
				}
				case WITH:
					// Completion for properties/methods of with object from #with
					Parameter object = ((WithSection) parentSection).getObjectParameter();
					if (object != null) {
						String projectUri = template.getProjectUri();
						ResolvedJavaTypeInfo withJavaTypeInfo = javaCache.resolveJavaType(object, projectUri)
								.getNow(null);
						if (withJavaTypeInfo != null) {
							JavaTypeFilter filter = javaCache.getJavaTypeFilter(projectUri, nativeImagesSettings);
							JavaTypeAccessibiltyRule javaTypeAccessibility = filter.getJavaTypeAccessibility(
									withJavaTypeInfo, template.getJavaTypesSupportedInNativeMode());
							fillCompletionFields(withJavaTypeInfo, javaTypeAccessibility, filter, range, projectUri,
									existingVars, completionItems);
							fillCompletionMethods(withJavaTypeInfo, javaTypeAccessibility, filter, range, projectUri,
									false, completionSettings, formattingSettings, existingVars, new HashSet<>(),
									completionItems);
						}
					}
					break;
				case WHEN:
				case SWITCH:
					if (node.getKind() == NodeKind.Expression) {
						Expression expression = (Expression) node;
						if (Section.isCaseSection(expression.getOwnerSection())) {
							// {#case | ...}
							Parameter triggeredParameter = expression.getOwnerParameter();
							CaseSection caseSection = (CaseSection) expression.getOwnerSection();
							// Completion for properties/methods of with object from #switch and #when
							Parameter value = ((WhenSection) parentSection).getValueParameter();
							if (value != null) {
								String projectUri = template.getProjectUri();
								ResolvedJavaTypeInfo whenJavaType = javaCache.resolveJavaType(value, projectUri)
										.getNow(null);
								if (whenJavaType != null && whenJavaType.isEnum()) {
									JavaTypeFilter filter = javaCache.getJavaTypeFilter(projectUri,
											nativeImagesSettings);
									JavaTypeAccessibiltyRule javaTypeAccessibility = filter.getJavaTypeAccessibility(
											whenJavaType, template.getJavaTypesSupportedInNativeMode());
									CompletionCaseResult result = caseSection.getCompletionCaseResultAt(offset,
											triggeredParameter);
									// Add existing variables
									Set<String> caseExistingVars = new HashSet<String>(existingVars);
									for (Parameter parameter : caseSection.getParameters()) {
										caseExistingVars.add(parameter.getName());
									}
									boolean canSupportMarkdown = completionSettings
											.canSupportMarkupKind(MarkupKind.MARKDOWN);
									// Complete operator and/or field according to result
									switch (result) {
									case ALL_OPERATOR_AND_FIELD:
										fillCaseOperators(caseSection, false, range, caseExistingVars, completionItems,
												canSupportMarkdown);
										fillCompletionFields(whenJavaType, javaTypeAccessibility, filter, range,
												projectUri, caseExistingVars, completionItems);
										break;
									case ALL_OPERATOR:
										fillCaseOperators(caseSection, false, range, caseExistingVars, completionItems,
												canSupportMarkdown);
										break;
									case FIELD_ONLY:
										fillCompletionFields(whenJavaType, javaTypeAccessibility, filter, range,
												projectUri, caseExistingVars, completionItems);
										break;
									case MULTI_OPERATOR_ONLY:
										fillCaseOperators(caseSection, true, range, caseExistingVars, completionItems,
												canSupportMarkdown);
										break;
									case NONE:
										break;
									}
								}
							}
						}
					}
				default:
				}
			}
		}
		doCompleteExpressionForObjectPartWithParentNodes(part, parentSection, range, offset, template, existingVars,
				completionSettings, formattingSettings, nativeImagesSettings, completionItems);
		return CompletableFuture.completedFuture(null);
	}

	private void doCompleteExpressionForObjectPartWithParameterAlias(Template template, Range range,
			Set<CompletionItem> completionItems) {
		template.getChildren().stream() //
				.filter(n -> n.getKind() == NodeKind.ParameterDeclaration) //
				.map(n -> ((ParameterDeclaration) n).getAlias()) //
				.filter(alias -> alias != null) //
				.forEach(alias -> {
					CompletionItem item = new CompletionItem();
					item.setLabel(alias);
					item.setKind(CompletionItemKind.Reference);
					TextEdit textEdit = new TextEdit(range, alias);
					item.setTextEdit(Either.forLeft(textEdit));
					completionItems.add(item);
				});
	}

	private void doCompleteExpressionForObjectPartWithCheckedTemplate(Template template, Range range,
			Set<CompletionItem> completionItems) {
		ExtendedDataModelTemplate dataModel = javaCache.getDataModelTemplate(template).getNow(null);
		if (dataModel == null || dataModel.getParameters() == null) {
			return;
		}
		for (ExtendedDataModelParameter parameter : dataModel.getParameters()) {
			CompletionItem item = new CompletionItem();
			item.setLabel(parameter.getKey());
			item.setKind(CompletionItemKind.Reference);
			TextEdit textEdit = new TextEdit(range, parameter.getKey());
			item.setTextEdit(Either.forLeft(textEdit));
			completionItems.add(item);
		}
	}
}
