package com.aptana.editor.js.contentassist.index;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;

import com.aptana.core.util.IOUtil;
import com.aptana.editor.js.Activator;
import com.aptana.editor.js.IJSConstants;
import com.aptana.editor.js.contentassist.JSASTQueryHelper;
import com.aptana.editor.js.parsing.IJSParserConstants;
import com.aptana.editor.js.parsing.JSParseState;
import com.aptana.editor.js.parsing.ast.JSFunctionNode;
import com.aptana.editor.js.parsing.ast.JSNode;
import com.aptana.index.core.IFileIndexingParticipant;
import com.aptana.index.core.Index;
import com.aptana.parsing.IParser;
import com.aptana.parsing.IParserPool;
import com.aptana.parsing.ParserPoolFactory;
import com.aptana.parsing.Scope;
import com.aptana.parsing.ast.IParseNode;

public class JSFileIndexingParticipant implements IFileIndexingParticipant
{
	private static final String JS_EXTENSION = "js"; //$NON-NLS-1$

	/*
	 * (non-Javadoc)
	 * @see com.aptana.index.core.IFileIndexingParticipant#index(java.util.Set, com.aptana.index.core.Index,
	 * org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public void index(Set<IFile> files, Index index, IProgressMonitor monitor)
	{
		monitor = SubMonitor.convert(monitor, files.size());

		for (IFile file : files)
		{
			if (monitor.isCanceled())
			{
				return;
			}

			try
			{
				if (file == null || !isJSFile(file))
				{
					continue;
				}

				monitor.subTask(file.getLocation().toPortableString());

				try
				{
					// grab the source of the file we're going to parse
					String source = IOUtil.read(file.getContents());

					// minor optimization when creating a new empty file
					if (source != null && source.length() > 0)
					{
						// create parser and associated parse state
						IParserPool pool = ParserPoolFactory.getInstance().getParserPool(IJSParserConstants.LANGUAGE);
						
						if (pool != null)
						{
							IParser parser = pool.checkOut();

							JSParseState parseState = new JSParseState();

							// apply the source to the parse state and parse
							parseState.setEditState(source, source, 0, 0);
							parser.parse(parseState);

							pool.checkIn(parser);

							// process results
							this.processParseResults(index, file, parseState);
						}
					}
				}
				catch (CoreException e)
				{
					Activator.logError(e.getMessage(), e);
				}
				catch (Exception e)
				{
					Activator.logError(e.getMessage(), e);
				}
			}
			finally
			{
				monitor.worked(1);
			}
		}

		monitor.done();
	}

	/**
	 * isJSFile
	 * 
	 * @param file
	 * @return
	 */
	private boolean isJSFile(IFile file)
	{
		InputStream stream = null;
		IContentTypeManager manager = Platform.getContentTypeManager();

		try
		{
			stream = file.getContents();

			IContentType[] types = manager.findContentTypesFor(stream, file.getName());

			for (IContentType type : types)
			{
				if (type.getId().equals(IJSConstants.CONTENT_TYPE_JS))
				{
					return true;
				}
			}
		}
		catch (Exception e)
		{
			Activator.logError(e.getMessage(), e);
		}
		finally
		{
			try
			{
				if (stream != null)
				{
					stream.close();
				}
			}
			catch (IOException e)
			{
				// ignore
			}
		}

		return JS_EXTENSION.equalsIgnoreCase(file.getFileExtension());
	}

	/**
	 * processParseResults
	 * 
	 * @param index
	 * @param file
	 * @param parseState
	 */
	private void processParseResults(Index index, IFile file, JSParseState parseState)
	{
		if (Platform.inDevelopmentMode())
		{
			String location = file.getProjectRelativePath().toPortableString();
			Scope<JSNode> globals = parseState.getGlobalScope();
			
			for (String symbol: globals.getLocalSymbolNames())
			{
				List<JSNode> nodes = globals.getSymbol(symbol);
				String category = JSIndexConstants.VARIABLE;
				
				for (JSNode node : nodes)
				{
					if (node instanceof JSFunctionNode)
					{
						category = JSIndexConstants.FUNCTION;
						break;
					}
				}
				
				index.addEntry(category, symbol, location);
			}
		}
		else
		{
			IParseNode ast = parseState.getParseResult();
			
			this.walkAST(index, file, ast);
		}
	}
	
	/**
	 * walkAST
	 * 
	 * @param index
	 * @param file
	 * @param ast
	 */
	private void walkAST(Index index, IFile file, IParseNode ast)
	{
		JSASTQueryHelper astHelper = new JSASTQueryHelper();
		String location = file.getProjectRelativePath().toPortableString();

		for (String name : astHelper.getChildFunctions(ast))
		{
			index.addEntry(JSIndexConstants.FUNCTION, name, location);
		}
		for (String varName : astHelper.getChildVarNonFunctions(ast))
		{
			index.addEntry(JSIndexConstants.VARIABLE, varName, location);
		}
		// for (String varName : astHelper.getAccidentalGlobals(ast))
		// {
		// System.out.println("accidental global: " + varName);
		// index.addEntry(JSIndexConstants.VARIABLE, varName, location);
		// }
	}
}
