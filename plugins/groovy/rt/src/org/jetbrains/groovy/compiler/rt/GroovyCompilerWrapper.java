package org.jetbrains.groovy.compiler.rt;

/*
 * Copyright 2000-2007 JetBrains s.r.o.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.lang.GroovyRuntimeException;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.*;
import org.codehaus.groovy.control.messages.*;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.tools.GroovyClass;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;


public class GroovyCompilerWrapper {
  private static final String LINKAGE_ERROR =
    "A groovyc error occurred while trying to load one of the classes in project dependencies, please ensure it's present. " +
    "See the message and the stack trace below for reference\n\n";
  private final List<CompilerMessage> collector;
  private final boolean forStubs;

  public GroovyCompilerWrapper(List<CompilerMessage> collector, boolean forStubs) {
    this.collector = collector;
    this.forStubs = forStubs;
  }

  public List<OutputItem> compile(final CompilationUnit unit) {
    List<OutputItem> compiledFiles = new ArrayList<OutputItem>();
    try {
      unit.compile(forStubs ? Phases.CONVERSION : Phases.ALL);
      addCompiledFiles(unit, compiledFiles, forStubs);
    }
    catch (CompilationFailedException e) {
      processCompilationException(e);
    }
    catch (IOException e) {
      processException(e, "");
    }
    catch (GroovyBugError e) {
      processException(e, "");
    }
    catch (NoClassDefFoundError e) {
      final String className = e.getMessage();
      if (className.startsWith("org/apache/ivy/")) {
        addMessageWithoutLocation("Cannot @Grab without Ivy, please add it to your module dependencies (NoClassDefFoundError: " + className + ")", true);
      } else {
        throw e;
      }

    }
    catch (TypeNotPresentException e) {
      processException(e, LINKAGE_ERROR);
    }
    catch (LinkageError e) {
      processException(e, LINKAGE_ERROR);
    }
    finally {
      addWarnings(unit.getErrorCollector());
    }
    return compiledFiles;
  }

  private static void addCompiledFiles(CompilationUnit compilationUnit,
                                       final List<OutputItem> compiledFiles,
                                       final boolean forStubs) throws IOException {
    File targetDirectory = compilationUnit.getConfiguration().getTargetDirectory();

    final String outputPath = targetDirectory.getAbsolutePath().replace(File.separatorChar, '/');

    if (forStubs) {
      compilationUnit.applyToPrimaryClassNodes(new CompilationUnit.PrimaryClassNodeOperation() {
        public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
          final String topLevel = classNode.getName();
          final String stubPath = outputPath + "/" + topLevel.replace('.', '/') + ".java";
          String fileName = source.getName();
          if (fileName.startsWith("file:")) {
            try {
              fileName = new URL(fileName).getFile();
            }
            catch (MalformedURLException ignored) {
            }
          }
          if (new File(stubPath).exists()) {
            compiledFiles.add(new OutputItem(stubPath, fileName));
          }
          /*
          else {
            collector.add(new CompilerMessage(CompilerMessage.WARNING, "Groovyc didn't generate stub for " + topLevel, fileName,
                                              classNode.getLineNumber(), classNode.getColumnNumber()));
          }
          */
        }
      });
      return;
    }

    final SortedSet<String> allClasses = new TreeSet<String>();
    //noinspection unchecked
    List<GroovyClass> listOfClasses = compilationUnit.getClasses();
    for (GroovyClass listOfClass : listOfClasses) {
      allClasses.add(listOfClass.getName());
    }

    for (Iterator iterator = compilationUnit.iterator(); iterator.hasNext();) {
      SourceUnit sourceUnit = (SourceUnit) iterator.next();
      String fileName = sourceUnit.getName();
      //for debug purposes
      //System.out.println("source: " + fileName);
      //System.out.print("classes:");
      final ModuleNode ast = sourceUnit.getAST();
      final List<ClassNode> topLevelClasses = ast.getClasses();

      for (ClassNode classNode : topLevelClasses) {
        final String topLevel = classNode.getName();
        final String nested = topLevel + "$";
        final SortedSet<String> tail = allClasses.tailSet(topLevel);
        for (Iterator<String> tailItr = tail.iterator(); tailItr.hasNext(); ) {
          String className = tailItr.next();
          if (className.equals(topLevel) || className.startsWith(nested)) {
            tailItr.remove();
            compiledFiles.add(new OutputItem(outputPath + "/" + className.replace('.', '/') + ".class", fileName));
          }
          else {
            break;
          }
        }
      }
    }
  }

  private void addWarnings(ErrorCollector errorCollector) {
    for (int i = 0; i < errorCollector.getWarningCount(); i++) {
      WarningMessage warning = errorCollector.getWarning(i);
      collector.add(new CompilerMessage(GroovyCompilerMessageCategories.WARNING, warning.getMessage(), null, -1, -1));
    }
  }

  private void processCompilationException(Exception exception) {
    if (exception instanceof MultipleCompilationErrorsException) {
      MultipleCompilationErrorsException multipleCompilationErrorsException = (MultipleCompilationErrorsException) exception;
      ErrorCollector errorCollector = multipleCompilationErrorsException.getErrorCollector();
      for (int i = 0; i < errorCollector.getErrorCount(); i++) {
        processException(errorCollector.getError(i));
      }
    } else {
      processException(exception, "");
    }
  }

  /** @noinspection ThrowableResultOfMethodCallIgnored*/
  private void processException(Message message) {
    if (message instanceof SyntaxErrorMessage) {
      SyntaxErrorMessage syntaxErrorMessage = (SyntaxErrorMessage) message;
      addErrorMessage(syntaxErrorMessage.getCause());
    } else if (message instanceof ExceptionMessage) {
      ExceptionMessage exceptionMessage = (ExceptionMessage) message;
      processException(exceptionMessage.getCause(), "");
    } else if (message instanceof SimpleMessage) {
      addErrorMessage((SimpleMessage) message);
    } else {
      addMessageWithoutLocation("An unknown error occurred: " + message, true);
    }
  }

  private void processException(Throwable exception, String prefix) {
    if (exception instanceof GroovyRuntimeException) {
      addErrorMessage((GroovyRuntimeException) exception);
      return;
    }

    if (forStubs) {
      collector.add(new CompilerMessage(GroovyCompilerMessageCategories.INFORMATION,
                                        GroovyRtConstants.GROOVYC_STUB_GENERATION_FAILED, null, -1, -1));
    }

    final StringWriter writer = new StringWriter();
    writer.append(prefix);
    //noinspection IOResourceOpenedButNotSafelyClosed
    exception.printStackTrace(new PrintWriter(writer));
    collector.add(new CompilerMessage(forStubs ? GroovyCompilerMessageCategories.INFORMATION : GroovyCompilerMessageCategories.ERROR, writer.toString(), null, -1, -1));
  }

  private void addMessageWithoutLocation(String message, boolean error) {
    collector.add(new CompilerMessage(error ? GroovyCompilerMessageCategories.ERROR : GroovyCompilerMessageCategories.WARNING, message, null, -1, -1));
  }

  private static final String LINE_AT = " @ line ";

  private void addErrorMessage(SyntaxException exception) {
    String message = exception.getMessage();
    String justMessage = message.substring(0, message.lastIndexOf(LINE_AT));
    collector.add(new CompilerMessage(GroovyCompilerMessageCategories.ERROR, justMessage, exception.getSourceLocator(),
        exception.getLine(), exception.getStartColumn()));
  }

  private void addErrorMessage(GroovyRuntimeException exception) {
    ASTNode astNode = exception.getNode();
    ModuleNode module = exception.getModule();
    if (module == null) {
      module = findModule(astNode);
    }
    String moduleName = module == null ? "<no module>" : module.getDescription();

    int lineNumber = astNode == null ? -1 : astNode.getLineNumber();
    int columnNumber = astNode == null ? -1 : astNode.getColumnNumber();

    String message = exception.getMessageWithoutLocationText();
    if (message == null) {
      StringWriter stringWriter = new StringWriter();
      //noinspection IOResourceOpenedButNotSafelyClosed
      PrintWriter writer = new PrintWriter(stringWriter);
      exception.printStackTrace(writer);
      message = stringWriter.getBuffer().toString();
    }

    collector.add(new CompilerMessage(GroovyCompilerMessageCategories.ERROR, message, moduleName, lineNumber, columnNumber));
  }

  private static ModuleNode findModule(ASTNode node) {
    if (node instanceof ModuleNode) {
      return (ModuleNode)node;
    }
    if (node instanceof ClassNode) {
      return ((ClassNode)node).getModule();
    }
    if (node instanceof AnnotatedNode) {
      return ((AnnotatedNode)node).getDeclaringClass().getModule();
    }
    return null;
  }

  private void addErrorMessage(SimpleMessage message) {
    addMessageWithoutLocation(message.getMessage(), true);
  }

  public static class OutputItem {
    private final String myOutputPath;
    private final String mySourceFileName;

    public OutputItem(String outputPath, String sourceFileName) {
      myOutputPath = outputPath;
      mySourceFileName = sourceFileName;
    }

    public String getOutputPath() {
      return myOutputPath;
    }

    public String getSourceFile() {
      return mySourceFileName;
    }
  }
}
