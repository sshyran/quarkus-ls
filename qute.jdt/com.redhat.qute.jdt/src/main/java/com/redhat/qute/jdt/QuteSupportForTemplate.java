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
package com.redhat.qute.jdt;

import static com.redhat.qute.jdt.utils.JDTTypeUtils.findType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.JavaProject;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.WorkspaceEdit;

import com.redhat.qute.commons.DocumentFormat;
import com.redhat.qute.commons.GenerateMissingJavaMemberParams;
import com.redhat.qute.commons.InvalidMethodReason;
import com.redhat.qute.commons.JavaFieldInfo;
import com.redhat.qute.commons.JavaMethodInfo;
import com.redhat.qute.commons.JavaTypeInfo;
import com.redhat.qute.commons.ProjectInfo;
import com.redhat.qute.commons.QuteJavaDefinitionParams;
import com.redhat.qute.commons.QuteJavaTypesParams;
import com.redhat.qute.commons.QuteJavadocParams;
import com.redhat.qute.commons.QuteProjectParams;
import com.redhat.qute.commons.QuteResolvedJavaTypeParams;
import com.redhat.qute.commons.ResolvedJavaTypeInfo;
import com.redhat.qute.commons.datamodel.DataModelParameter;
import com.redhat.qute.commons.datamodel.DataModelProject;
import com.redhat.qute.commons.datamodel.DataModelTemplate;
import com.redhat.qute.commons.datamodel.QuteDataModelProjectParams;
import com.redhat.qute.commons.usertags.QuteUserTagParams;
import com.redhat.qute.commons.usertags.UserTagInfo;
import com.redhat.qute.jdt.internal.resolver.AbstractTypeResolver;
import com.redhat.qute.jdt.internal.resolver.ClassFileTypeResolver;
import com.redhat.qute.jdt.internal.resolver.CompilationUnitTypeResolver;
import com.redhat.qute.jdt.internal.resolver.ITypeResolver;
import com.redhat.qute.jdt.internal.template.JavaTypesSearch;
import com.redhat.qute.jdt.internal.template.QuarkusIntegrationForQute;
import com.redhat.qute.jdt.internal.template.QuteSupportForTemplateGenerateMissingJavaMemberHandler;
import com.redhat.qute.jdt.internal.template.TemplateDataSupport;
import com.redhat.qute.jdt.utils.IJDTUtils;
import com.redhat.qute.jdt.utils.JDTQuteProjectUtils;
import com.redhat.qute.jdt.utils.JDTTypeUtils;
import com.redhat.qute.jdt.utils.QuteReflectionAnnotationUtils;

/**
 *
 * Qute support for Template file.
 *
 * @author Angelo ZERR
 *
 */
public class QuteSupportForTemplate {

	private static final Logger LOGGER = Logger.getLogger(QuteSupportForTemplate.class.getName());

	private static final String JAVA_LANG_OBJECT = "java.lang.Object";

	private static final QuteSupportForTemplate INSTANCE = new QuteSupportForTemplate();

	public static QuteSupportForTemplate getInstance() {
		return INSTANCE;
	}

	/**
	 * Returns the project information for the given project Uri.
	 *
	 * @param params  the project information parameters.
	 * @param utils   the JDT LS utility.
	 * @param monitor the progress monitor.
	 *
	 * @return the project information for the given project Uri and null otherwise.
	 */
	public ProjectInfo getProjectInfo(QuteProjectParams params, IJDTUtils utils, IProgressMonitor monitor) {
		IJavaProject javaProject = getJavaProjectFromTemplateFile(params.getTemplateFileUri(), utils);
		if (javaProject == null) {
			return null;
		}
		return JDTQuteProjectUtils.getProjectInfo(javaProject);
	}

	/**
	 * Collect data model templates from the given project Uri. A data model
	 * template can be declared with:
	 *
	 * <ul>
	 * <li>@CheckedTemplate support: collect parameters for Qute Template by
	 * searching @CheckedTemplate annotation.</li>
	 * <li>Template field support: collect parameters for Qute Template by searching
	 * Template instance declared as field in Java class.</li>
	 * <li>Template extension support: see
	 * https://quarkus.io/guides/qute-reference#template_extension_methods</li>
	 * </ul>
	 *
	 * @param params  the project uri.
	 * @param utils   JDT LS utilities
	 * @param monitor the progress monitor
	 *
	 * @return data model templates from the given project Uri.
	 *
	 * @throws CoreException
	 */
	public DataModelProject<DataModelTemplate<DataModelParameter>> getDataModelProject(
			QuteDataModelProjectParams params, IJDTUtils utils, IProgressMonitor monitor) throws CoreException {
		String projectUri = params.getProjectUri();
		IJavaProject javaProject = getJavaProjectFromProjectUri(projectUri);
		if (javaProject == null) {
			return null;
		}
		return QuarkusIntegrationForQute.getDataModelProject(javaProject, monitor);
	}

	/**
	 * Collect user tags from the given project Uri.
	 *
	 * @param params  the project uri.
	 * @param utils   JDT LS utilities
	 * @param monitor the progress monitor
	 *
	 * @return user tags from the given project Uri.
	 *
	 * @throws CoreException
	 */
	public List<UserTagInfo> getUserTags(QuteUserTagParams params, IJDTUtils utils, IProgressMonitor monitor)
			throws CoreException {
		String projectUri = params.getProjectUri();
		IJavaProject javaProject = getJavaProjectFromProjectUri(projectUri);
		if (javaProject == null) {
			return null;
		}
		return QuarkusIntegrationForQute.getUserTags(javaProject, monitor);
	}

	/**
	 * Returns Java types for the given pattern which belong to the given project
	 * Uri.
	 *
	 * @param params  the java types parameters.
	 * @param utils   the JDT LS utility.
	 * @param monitor the progress monitor.
	 *
	 * @return list of Java types.
	 *
	 * @throws CoreException
	 */
	public List<JavaTypeInfo> getJavaTypes(QuteJavaTypesParams params, IJDTUtils utils, IProgressMonitor monitor)
			throws CoreException {
		String projectUri = params.getProjectUri();
		IJavaProject javaProject = getJavaProjectFromProjectUri(projectUri);
		if (javaProject == null) {
			return null;
		}
		return new JavaTypesSearch(params.getPattern(), javaProject).search(monitor);
	}

	/**
	 * Returns the Java definition of the given Java type, method, field, method
	 * parameter, method invocation parameter and null otherwise.
	 *
	 * @param params  the Java element information.
	 * @param utils   the JDT LS utility.
	 * @param monitor the progress monitor.
	 *
	 * @return the Java definition of the given Java type, method, field, method
	 *         parameter, method invocation parameter and null otherwise.
	 * @throws CoreException
	 */
	public Location getJavaDefinition(QuteJavaDefinitionParams params, IJDTUtils utils, IProgressMonitor monitor)
			throws CoreException {
		IType type = getTypeFromParams(params.getSourceType(), params.getProjectUri(), monitor);
		if (type == null) {
			return null;
		}

		String parameterName = params.getSourceParameter();
		boolean dataMethodInvocation = parameterName != null && params.isDataMethodInvocation();

		String fieldName = params.getSourceField();
		if (fieldName != null) {
			IField field = type.getField(fieldName);
			if (field == null || !field.exists()) {
				// The field doesn't exist
				return null;
			}

			if (dataMethodInvocation) {
				// returns the location of "data" method invocation with the given parameter
				// name
				return TemplateDataSupport.getDataMethodInvocationLocation(field, parameterName, utils, monitor);
			}
			// returns field location
			return utils.toLocation(field);
		}

		String sourceMethod = params.getSourceMethod();
		if (sourceMethod != null) {
			IMethod method = findMethod(type, sourceMethod);
			if (method == null || !method.exists()) {
				// The method doesn't exist
				return null;
			}

			if (parameterName != null) {
				if (dataMethodInvocation) {
					// returns the location of "data" method invocation with the given parameter
					// name
					return TemplateDataSupport.getDataMethodInvocationLocation(method, parameterName, utils, monitor);
				}
				ILocalVariable[] parameters = method.getParameters();
				for (ILocalVariable parameter : parameters) {
					if (parameterName.equals(parameter.getElementName())) {
						// returns the method parameter location
						return utils.toLocation(parameter);
					}
				}
				return null;
			}
			// returns method location
			return utils.toLocation(method);
		}
		// returns Java type location
		return utils.toLocation(type);
	}

	private IMethod findMethod(IType type, String sourceMethod) throws JavaModelException {
		// For the moment we search method only by name
		// FIXME:use method signature to retrieve the proper method (see findMethodOLD)
		IMethod[] methods = type.getMethods();
		for (IMethod method : methods) {
			if (sourceMethod.equals(method.getElementName())) {
				return method;
			}
		}
		return null;
	}

	private IMethod findMethodOLD(IType type, String sourceMethod) throws JavaModelException {
		int startBracketIndex = sourceMethod.indexOf('(');
		String methodName = sourceMethod.substring(0, startBracketIndex);
		// Method signature has been generated with JDT API, so we are sure that we have
		// a ')' character.
		int endBracketIndex = sourceMethod.indexOf(')');
		String methodSignature = sourceMethod.substring(startBracketIndex, endBracketIndex + 1);
		String[] paramTypes = methodSignature.isEmpty() ? CharOperation.NO_STRINGS
				: Signature.getParameterTypes(methodSignature);

		// try findMethod for non constructor. If result is null, findMethod for
		// constructor
		IMethod method = JavaModelUtil.findMethod(methodName, paramTypes, false, type);
		if (method == null) {
			method = JavaModelUtil.findMethod(methodName, paramTypes, true, type);
		}
		return method;
	}

	/**
	 * Returns the resolved type (fields and methods) for the given Java type.
	 *
	 * @param params  the Java type to resolve.
	 * @param utils   the JDT LS utility.
	 * @param monitor the progress monitor.
	 *
	 * @return the resolved type (fields and methods) for the given Java type.
	 *
	 * @throws CoreException
	 */
	public ResolvedJavaTypeInfo getResolvedJavaType(QuteResolvedJavaTypeParams params, IJDTUtils utils,
			IProgressMonitor monitor) throws CoreException {
		if (monitor.isCanceled()) {
			throw new OperationCanceledException();
		}
		String projectUri = params.getProjectUri();
		IJavaProject javaProject = getJavaProjectFromProjectUri(projectUri);
		if (javaProject == null) {
			return null;
		}
		String typeName = params.getClassName();

		// ex : org.acme.Item, java.util.List, ...
		IType type = findType(typeName, javaProject, monitor);
		if (type == null) {
			return null;
		}

		ITypeResolver typeResolver = createTypeResolver(type);

		// 1) Collect fields
		List<JavaFieldInfo> fieldsInfo = new ArrayList<>();

		// Standard fields
		IField[] fields = type.getFields();
		for (IField field : fields) {
			if (isValidField(field, type)) {
				// Only public fields are available
				JavaFieldInfo info = new JavaFieldInfo();
				info.setSignature(typeResolver.resolveFieldSignature(field));
				fieldsInfo.add(info);
			}
		}

		// Record fields
		if (type.isRecord()) {
			for (IField field : type.getRecordComponents()) {
				// All record components are valid
				JavaFieldInfo info = new JavaFieldInfo();
				info.setSignature(typeResolver.resolveFieldSignature(field));
				fieldsInfo.add(info);
			}
		}

		// 2) Collect methods
		List<JavaMethodInfo> methodsInfo = new ArrayList<>();
		Map<String, InvalidMethodReason> invalidMethods = new HashMap<>();
		IMethod[] methods = type.getMethods();
		for (IMethod method : methods) {
			if (isValidMethod(method, type)) {
				try {
					InvalidMethodReason invalid = getValidMethodForQute(method, typeName);
					if (invalid != null) {
						invalidMethods.put(method.getElementName(), invalid);
					} else {
						JavaMethodInfo info = new JavaMethodInfo();
						info.setSignature(typeResolver.resolveMethodSignature(method));
						methodsInfo.add(info);
					}
				} catch (Exception e) {
					LOGGER.log(Level.SEVERE,
							"Error while getting method signature of '" + method.getElementName() + "'.", e);
				}
			}
		}

		ResolvedJavaTypeInfo resolvedType = new ResolvedJavaTypeInfo();
		String typeSignature = AbstractTypeResolver.resolveJavaTypeSignature(type);
		resolvedType.setBinary(type.isBinary());
		resolvedType.setSignature(typeSignature);
		resolvedType.setFields(fieldsInfo);
		resolvedType.setMethods(methodsInfo);
		resolvedType.setInvalidMethods(invalidMethods);
		resolvedType.setExtendedTypes(typeResolver.resolveExtendedType());
		resolvedType.setJavaTypeKind(JDTTypeUtils.getJavaTypeKind(type));
		QuteReflectionAnnotationUtils.collectAnnotations(resolvedType, type, typeResolver);
		return resolvedType;
	}

	private static boolean isValidField(IField field, IType type) throws JavaModelException {
		if (type.isEnum()) {
			return true;
		}
		return Flags.isPublic(field.getFlags());
	}

	private static boolean isValidMethod(IMethod method, IType type) {
		try {
			if (method.isConstructor() || !method.exists() || Flags.isSynthetic(method.getFlags())) {
				return false;
			}
			if (!type.isInterface() && !Flags.isPublic(method.getFlags())) {
				return false;
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Error while checking if '" + method.getElementName() + "' is valid.", e);
			return false;
		}
		return true;
	}

	/**
	 * Returns the reason
	 *
	 * @param method
	 * @param type
	 *
	 * @return
	 *
	 * @see https://github.com/quarkusio/quarkus/blob/ce19ff75e9f732ff731bb30c2141b44b42c66050/independent-projects/qute/core/src/main/java/io/quarkus/qute/ReflectionValueResolver.java#L176
	 */
	private static InvalidMethodReason getValidMethodForQute(IMethod method, String typeName) {
		if (JAVA_LANG_OBJECT.equals(typeName)) {
			return InvalidMethodReason.FromObject;
		}
		try {
			if ("V".equals(method.getReturnType())) {
				return InvalidMethodReason.VoidReturn;
			}
			if (Flags.isStatic(method.getFlags())) {
				return InvalidMethodReason.Static;
			}
		} catch (JavaModelException e) {
			LOGGER.log(Level.SEVERE, "Error while checking if '" + method.getElementName() + "' is valid.", e);
		}
		return null;
	}

	private static IJavaProject getJavaProjectFromProjectUri(String projectName) {
		if (projectName == null) {
			return null;
		}
		IJavaProject javaProject = JavaModelManager.getJavaModelManager().getJavaModel().getJavaProject(projectName);
		return javaProject.exists() ? javaProject : null;
	}

	private static IJavaProject getJavaProjectFromTemplateFile(String templateFileUri, IJDTUtils utils) {
		templateFileUri = templateFileUri.replace("vscode-notebook-cell", "file");
		IFile file = utils.findFile(templateFileUri);
		if (file == null || file.getProject() == null) {
			// The uri doesn't belong to an Eclipse project
			return null;
		}
		// The uri belong to an Eclipse project
		if (!(JavaProject.hasJavaNature(file.getProject()))) {
			// The uri doesn't belong to a Java project
			return null;
		}

		String projectName = file.getProject().getName();
		return JavaModelManager.getJavaModelManager().getJavaModel().getJavaProject(projectName);
	}

	private static IType[] findImplementedInterfaces(IType type, IProgressMonitor progressMonitor)
			throws JavaModelException {
		ITypeHierarchy typeHierarchy = type.newSupertypeHierarchy(progressMonitor);
		return typeHierarchy.getRootInterfaces();
	}

	public static ITypeResolver createTypeResolver(IMember member) {
		ITypeResolver typeResolver = !member.isBinary()
				? new CompilationUnitTypeResolver((ICompilationUnit) member.getAncestor(IJavaElement.COMPILATION_UNIT))
				: new ClassFileTypeResolver((IClassFile) member.getAncestor(IJavaElement.CLASS_FILE));
		return typeResolver;
	}

	/**
	 * Returns the workspace edit to generate the given java member for the given
	 * type.
	 *
	 * @param params  the parameters needed to resolve the workspace edit
	 * @param utils   the jdt utils
	 * @param monitor the progress monitor
	 * @return the workspace edit to generate the given java member for the given
	 *         type
	 */
	public WorkspaceEdit generateMissingJavaMember(GenerateMissingJavaMemberParams params, IJDTUtils utils,
			IProgressMonitor monitor) {
		return QuteSupportForTemplateGenerateMissingJavaMemberHandler.handleGenerateMissingJavaMember(params, utils,
				monitor);
	}

	/**
	 * Returns the formatted Javadoc for the member specified in the parameters.
	 *
	 * @param params  the parameters used to specify the member whose documentation
	 *                should be found
	 * @param utils   the JDT utils
	 * @param monitor the progress monitor
	 * @return the formatted Javadoc for the member specified in the parameters
	 */
	public String getJavadoc(QuteJavadocParams params, IJDTUtils utils, IProgressMonitor monitor) {
		try {
			IType type = getTypeFromParams(params.getSourceType(), params.getProjectUri(), monitor);
			if (type == null) {
				return null;
			}
			return getJavadoc(type, params.getDocumentFormat(), params.getMemberName(), params.getSignature(), utils,
					monitor, new HashSet<>());
		} catch (JavaModelException e) {
			LOGGER.log(Level.SEVERE,
					"Error while collecting Javadoc for " + params.getSourceType() + "#" + params.getMemberName(), e);
			return null;
		}
	}

	private String getJavadoc(IType type, DocumentFormat documentFormat, String memberName, String signature, IJDTUtils utils, IProgressMonitor monitor, Set<IType> visited) throws JavaModelException {
		if (visited.contains(type)) {
			return null;
		}
		visited.add(type);
		if (monitor.isCanceled()) {
			throw new OperationCanceledException();
		}

		ITypeResolver typeResolver = createTypeResolver(type);

		// 1) Check the fields for the member

		// Standard fields
		IField[] fields = type.getFields();
		for (IField field : fields) {
			if (isValidField(field, type)
					&& memberName.equals(field.getElementName())
					&& signature.equals(typeResolver.resolveFieldSignature(field))) {
				String javadoc = utils.getJavadoc(field, documentFormat);
				if (javadoc != null) {
					return javadoc;
				}
			}
		}

		// Record fields
		if (type.isRecord()) {
			for (IField field : type.getRecordComponents()) {
				// All record components are valid
				if (memberName.equals(field.getElementName())
						&& signature.equals(typeResolver.resolveFieldSignature(field))) {
					String javadoc = utils.getJavadoc(field, documentFormat);
					if (javadoc != null) {
						return javadoc;
					}
				}
			}
		}

		// 2) Check the methods for the member
		IMethod[] methods = type.getMethods();
		for (IMethod method : methods) {
			if (isValidMethod(method, type)) {
				try {
					InvalidMethodReason invalid = getValidMethodForQute(method, type.getFullyQualifiedName());
					if (invalid == null && (signature.equals(typeResolver.resolveMethodSignature(method)))) {
						String javadoc = utils.getJavadoc(method, documentFormat);
						if (javadoc != null) {
							return javadoc;
						}
						// otherwise, maybe a supertype has it
					}
				} catch (Exception e) {
					LOGGER.log(Level.SEVERE,
							"Error while getting method signature of '" + method.getElementName() + "'.", e);
				}
			}
		}

		// 3) Check the superclasses for the member

		// Collect type extensions
		List<IType> extendedTypes = null;
		if (type.isInterface()) {
			IType[] interfaces = findImplementedInterfaces(type, monitor);
			if (interfaces != null && interfaces.length > 0) {
				extendedTypes = Arrays.asList(interfaces);
			}
		} else {
			// ex : String implements CharSequence, ....
			ITypeHierarchy typeHierarchy = type.newSupertypeHierarchy(monitor);
			IType[] allSuperTypes = typeHierarchy.getSupertypes(type);
			extendedTypes = Arrays.asList(allSuperTypes);
		}

		if (extendedTypes != null) {
			for (IType extendedType : extendedTypes) {
				String javadoc = getJavadoc(extendedType, documentFormat, memberName, signature, utils, monitor, visited);
				if (javadoc != null) {
					return javadoc;
				}
			}
		}

		return null;

	}

	private IType getTypeFromParams(String typeName, String projectUri, IProgressMonitor monitor) throws JavaModelException {
		IJavaProject javaProject = getJavaProjectFromProjectUri(projectUri);
		if (javaProject == null) {
			return null;
		}
		IType type = findType(typeName, javaProject, monitor);
		return type;
	}

}
