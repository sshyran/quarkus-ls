/*******************************************************************************
* Copyright (c) 2019 Red Hat Inc. and others.
* All rights reserved. This program and the accompanying materials
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v20.html
*
* Contributors:
*     Red Hat Inc. - initial API and implementation
*******************************************************************************/
package com.redhat.quarkus.ls;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

import com.redhat.quarkus.commons.ExtendedConfigDescriptionBuildItem;
import com.redhat.quarkus.commons.QuarkusProjectInfoParams;
import com.redhat.quarkus.ls.commons.BadLocationException;
import com.redhat.quarkus.ls.commons.TextDocument;
import com.redhat.quarkus.ls.commons.TextDocuments;
import com.redhat.quarkus.services.QuarkusLanguageService;
import com.redhat.quarkus.settings.SharedSettings;

/**
 * Quarkus text document service.
 *
 */
public class QuarkusTextDocumentService implements TextDocumentService {

	private final TextDocuments<TextDocument> documents;

	private final QuarkusProjectInfoCache projectInfoCache;

	private final QuarkusLanguageServer quarkusLanguageServer;

	private final SharedSettings sharedSettings;

	public QuarkusTextDocumentService(QuarkusLanguageServer quarkusLanguageServer) {
		this.quarkusLanguageServer = quarkusLanguageServer;
		this.documents = new TextDocuments<TextDocument>();
		this.projectInfoCache = new QuarkusProjectInfoCache(quarkusLanguageServer);
		this.sharedSettings = new SharedSettings();
	}

	/**
	 * Update shared settings from the client capabilities.
	 * 
	 * @param capabilities the client capabilities
	 */
	public void updateClientCapabilities(ClientCapabilities capabilities) {
		TextDocumentClientCapabilities textDocumentClientCapabilities = capabilities.getTextDocument();
		if (textDocumentClientCapabilities != null) {
			sharedSettings.getCompletionSettings().setCapabilities(textDocumentClientCapabilities.getCompletion());
			sharedSettings.getHoverSettings().setCapabilities(textDocumentClientCapabilities.getHover());
		}
	}

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		TextDocument document = documents.onDidOpenTextDocument(params);
		triggerValidationFor(document);
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		TextDocument document = documents.onDidChangeTextDocument(params);
		triggerValidationFor(document);
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		documents.onDidCloseTextDocument(params);
		TextDocumentIdentifier document = params.getTextDocument();
		String uri = document.getUri();
		quarkusLanguageServer.getLanguageClient()
				.publishDiagnostics(new PublishDiagnosticsParams(uri, new ArrayList<Diagnostic>()));
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {

	}

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		QuarkusProjectInfoParams projectInfoParams = createProjectInfoParams(params.getTextDocument(), null);
		return projectInfoCache.getQuarkusProjectInfo(projectInfoParams).thenApplyAsync(projectInfo -> {
			if (!projectInfo.isQuarkusProject()) {
				return Either.forRight(new CompletionList());
			}
			String uri = params.getTextDocument().getUri();
			TextDocument document = documents.get(uri);
			CompletionList list = getQuarkusLanguageService().doComplete(document, params.getPosition(), projectInfo,
					sharedSettings.getCompletionSettings(), null);
			return Either.forRight(list);
		});
	}

	@Override
	public CompletableFuture<Hover> hover(TextDocumentPositionParams params) {

		QuarkusProjectInfoParams projectInfoParams = createProjectInfoParams(params.getTextDocument(), null);
		return projectInfoCache.getQuarkusProjectInfo(projectInfoParams).thenApplyAsync(projectInfo -> {
			if (!projectInfo.isQuarkusProject()) {
				return null;
			}
			
			String uri = params.getTextDocument().getUri();
			TextDocument document = documents.get(uri);
			return getQuarkusLanguageService().doHover(document, params.getPosition(), projectInfo, sharedSettings.getHoverSettings());
		});
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			TextDocumentPositionParams params) {
		QuarkusProjectInfoParams projectInfoParams = createProjectInfoParams(params.getTextDocument(), null);
		return projectInfoCache.getQuarkusProjectInfo(projectInfoParams).thenCompose(projectInfo -> {
			if (!projectInfo.isQuarkusProject()) {
				return null;
			}

			String uri = params.getTextDocument().getUri();
			TextDocument document = documents.get(uri);
			String line = null;
			try {
				line = document.lineText(params.getPosition().getLine());
			} catch (BadLocationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			String propertyName = line.substring(0, line.indexOf('=')).trim();
			Optional<ExtendedConfigDescriptionBuildItem> result = projectInfo.getProperties().stream()
					.filter(p -> p.getPropertyName().equals(propertyName)).findFirst();
			if (result.isPresent()) {
				String source = result.get().getSource();
				return quarkusLanguageServer.findDefinition(uri, source).thenApplyAsync(loc -> {
					List<Location> locations = new ArrayList<>();
					locations.add(loc);
					return Either.forLeft(locations);
				});
			}
			return null;
		});
	}

	private static QuarkusProjectInfoParams createProjectInfoParams(TextDocumentIdentifier id,
			List<String> documentationFormat) {
		return new QuarkusProjectInfoParams(id.getUri(), documentationFormat);
	}

	private QuarkusLanguageService getQuarkusLanguageService() {
		return quarkusLanguageServer.getQuarkusLanguageService();
	}

	private void triggerValidationFor(TextDocument document) {
		// TODO: implement validation for application.properties
	}

}
