package com.intellij.structuralsearch;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.template.JavaCodeContextType;
import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.dupLocator.iterators.NodeIterator;
import com.intellij.lang.Language;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.structuralsearch.impl.matcher.*;
import com.intellij.structuralsearch.impl.matcher.compiler.GlobalCompilingVisitor;
import com.intellij.structuralsearch.impl.matcher.compiler.JavaCompilingVisitor;
import com.intellij.structuralsearch.impl.matcher.compiler.PatternCompiler;
import com.intellij.structuralsearch.impl.matcher.filters.JavaLexicalNodesFilter;
import com.intellij.structuralsearch.impl.matcher.filters.LexicalNodesFilter;
import com.intellij.structuralsearch.plugin.replace.ReplaceOptions;
import com.intellij.structuralsearch.plugin.replace.impl.ParameterInfo;
import com.intellij.structuralsearch.plugin.replace.impl.ReplacementBuilder;
import com.intellij.structuralsearch.plugin.replace.impl.ReplacementContext;
import com.intellij.structuralsearch.plugin.replace.impl.Replacer;
import com.intellij.structuralsearch.plugin.ui.Configuration;
import com.intellij.structuralsearch.plugin.ui.SearchContext;
import com.intellij.structuralsearch.plugin.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Eugene.Kudelevsky
 */
public class JavaStructuralSearchProfile extends StructuralSearchProfile {
  private JavaLexicalNodesFilter myJavaLexicalNodesFilter;

  public String getText(PsiElement match, int start,int end) {
    if (match instanceof PsiIdentifier) {
      PsiElement parent = match.getParent();
      if (parent instanceof PsiJavaCodeReferenceElement && !(parent instanceof PsiExpression)) {
        match = parent; // care about generic
      }
    }
    final String matchText = match.getText();
    if (start==0 && end==-1) return matchText;
    return matchText.substring(start,end == -1? matchText.length():end);
  }

  public Class getElementContextByPsi(PsiElement element) {
    if (element instanceof PsiIdentifier) {
      element = element.getParent();
    }

    if (element instanceof PsiMember) {
      return PsiMember.class;
    } else {
      return PsiExpression.class;
    }
  }

  @NotNull
  public String getTypedVarString(final PsiElement element) {
    String text;

    if (element instanceof PsiNamedElement) {
      text = ((PsiNamedElement)element).getName();
    }
    else if (element instanceof PsiAnnotation) {
      PsiJavaCodeReferenceElement referenceElement = ((PsiAnnotation)element).getNameReferenceElement();
      text = referenceElement == null ? null : referenceElement.getQualifiedName();
    }
    else if (element instanceof PsiNameValuePair) {
      text = ((PsiNameValuePair)element).getName();
    }
    else {
      text = element.getText();
      if (StringUtil.startsWithChar(text, '@')) {
        text = text.substring(1);
      }
      if (StringUtil.endsWithChar(text, ';')) text = text.substring(0, text.length() - 1);
      else if (element instanceof PsiExpressionStatement) {
        int i = text.indexOf(';');
        if (i != -1) text = text.substring(0, i);
      }
    }

    if (text==null) text = element.getText();

    return text;
  }

  @Override
  public String getMeaningfulText(PsiElement element) {
    if (element instanceof PsiReferenceExpression &&
        ((PsiReferenceExpression)element).getQualifierExpression() != null) {
      final PsiElement resolve = ((PsiReferenceExpression)element).resolve();
      if (resolve instanceof PsiClass) return element.getText();

      final PsiElement referencedElement = ((PsiReferenceExpression)element).getReferenceNameElement();
      String text = referencedElement != null ? referencedElement.getText() : "";

      if (resolve == null && text.length() > 0 && Character.isUpperCase(text.charAt(0))) {
        return element.getText();
      }
      return text;
    }
    return super.getMeaningfulText(element);
  }

  @Override
  public PsiElement updateCurrentNode(PsiElement targetNode) {
    if (targetNode instanceof PsiCodeBlock && ((PsiCodeBlock)targetNode).getStatements().length == 1) {
      PsiElement targetNodeParent = targetNode.getParent();
      if (targetNodeParent instanceof PsiBlockStatement) {
        targetNodeParent = targetNodeParent.getParent();
      }

      if (targetNodeParent instanceof PsiIfStatement || targetNodeParent instanceof PsiLoopStatement) {
        targetNode = targetNodeParent;
      }
    }
    return targetNode;
  }

  @Override
  public PsiElement extendMatchedByDownUp(PsiElement targetNode) {
    if (targetNode instanceof PsiIdentifier) {
      targetNode = targetNode.getParent();
      final PsiElement parent = targetNode.getParent();
      if (parent instanceof PsiTypeElement || parent instanceof PsiStatement) targetNode = parent;
    }
    return targetNode;
  }

  @Override
  public PsiElement extendMatchOnePsiFile(PsiElement file) {
    if (file instanceof PsiIdentifier) {
      // Searching in previous results
      file = file.getParent();
    }
    return file;
  }

  public void compile(PsiElement[] elements, @NotNull GlobalCompilingVisitor globalVisitor) {
    elements[0].getParent().accept(new JavaCompilingVisitor(globalVisitor));
  }

  @NotNull
  public PsiElementVisitor createMatchingVisitor(@NotNull GlobalMatchingVisitor globalVisitor) {
    return new JavaMatchingVisitor(globalVisitor);
  }

  @NotNull
  @Override
  public PsiElementVisitor getLexicalNodesFilter(@NotNull LexicalNodesFilter filter) {
    if (myJavaLexicalNodesFilter == null) {
      myJavaLexicalNodesFilter = new JavaLexicalNodesFilter(filter);
    }
    return myJavaLexicalNodesFilter;
  }

  @NotNull
  public CompiledPattern createCompiledPattern() {
    return new JavaCompiledPattern();
  }

  public boolean isMyLanguage(@NotNull Language language) {
    return language == JavaLanguage.INSTANCE;
  }

  @Override
  public StructuralReplaceHandler getReplaceHandler(@NotNull ReplacementContext context) {
    return new JavaReplaceHandler(context);
  }

  @NotNull
  @Override
  public PsiElement[] createPatternTree(@NotNull String text,
                                        @NotNull PatternTreeContext context,
                                        @NotNull FileType fileType,
                                        @Nullable Language language,
                                        String contextName, @Nullable String extension,
                                        @NotNull Project project,
                                        boolean physical) {
    if (physical) {
      throw new UnsupportedOperationException(getClass() + " cannot create physical PSI");
    }
    PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
    if (context == PatternTreeContext.Block) {
      PsiElement element = elementFactory.createStatementFromText("{\n" + text + "\n}", null);
      final PsiElement[] children = ((PsiBlockStatement)element).getCodeBlock().getChildren();
      final int extraChildCount = 4;

      if (children.length > extraChildCount) {
        PsiElement[] result = new PsiElement[children.length - extraChildCount];
        final int extraChildStart = 2;
        System.arraycopy(children, extraChildStart, result, 0, children.length - extraChildCount);
        return result;
      }
      else {
        return PsiElement.EMPTY_ARRAY;
      }
    }
    else if (context == PatternTreeContext.Class) {
      PsiElement element = elementFactory.createStatementFromText("class A {\n" + text + "\n}", null);
      PsiClass clazz = (PsiClass)((PsiDeclarationStatement)element).getDeclaredElements()[0];
      PsiElement startChild = clazz.getLBrace();
      if (startChild != null) startChild = startChild.getNextSibling();

      PsiElement endChild = clazz.getRBrace();
      if (endChild != null) endChild = endChild.getPrevSibling();
      if (startChild == endChild) return PsiElement.EMPTY_ARRAY; // nothing produced

      final List<PsiElement> result = new ArrayList<PsiElement>(3);
      assert startChild != null;
      for (PsiElement el = startChild.getNextSibling(); el != endChild && el != null; el = el.getNextSibling()) {
        if (el instanceof PsiErrorElement) continue;
        result.add(el);
      }

      return PsiUtilCore.toPsiElementArray(result);
    }
    else {
      return PsiFileFactory.getInstance(project).createFileFromText("__dummy.java", text).getChildren();
    }
  }

  @NotNull
  @Override
  public Editor createEditor(@NotNull SearchContext searchContext,
                             @NotNull FileType fileType,
                             Language dialect,
                             String text,
                             boolean useLastConfiguration) {
    // provides autocompletion

    PsiElement element = searchContext.getFile();

    if (element != null && !useLastConfiguration) {
      final Editor selectedEditor = FileEditorManager.getInstance(searchContext.getProject()).getSelectedTextEditor();

      if (selectedEditor != null) {
        int caretPosition = selectedEditor.getCaretModel().getOffset();
        PsiElement positionedElement = searchContext.getFile().findElementAt(caretPosition);

        if (positionedElement == null) {
          positionedElement = searchContext.getFile().findElementAt(caretPosition + 1);
        }

        if (positionedElement != null) {
          element = PsiTreeUtil.getParentOfType(
            positionedElement,
            PsiClass.class, PsiCodeBlock.class
          );
        }
      }
    }

    final PsiManager psimanager = PsiManager.getInstance(searchContext.getProject());
    final Project project = psimanager.getProject();
    final PsiCodeFragment file = createCodeFragment(project, text, element);
    final Document doc = PsiDocumentManager.getInstance(searchContext.getProject()).getDocument(file);
    DaemonCodeAnalyzer.getInstance(searchContext.getProject()).setHighlightingEnabled(file, false);
    return UIUtil.createEditor(doc, searchContext.getProject(), true, true, getTemplateContextType());
  }

  @Override
  public Class<? extends TemplateContextType> getTemplateContextTypeClass() {
    return JavaCodeContextType.class;
  }

  public PsiCodeFragment createCodeFragment(Project project, String text, PsiElement context) {
    final JavaCodeFragmentFactory factory = JavaCodeFragmentFactory.getInstance(project);
    return factory.createCodeBlockCodeFragment(text, context, true);
  }

  @Override
  public void checkSearchPattern(Project project, MatchOptions options) {
    class ValidatingVisitor extends JavaRecursiveElementWalkingVisitor {
      private PsiElement myCurrent;

      @Override public void visitAnnotation(PsiAnnotation annotation) {
        final PsiJavaCodeReferenceElement nameReferenceElement = annotation.getNameReferenceElement();

        if (nameReferenceElement == null ||
            !nameReferenceElement.getText().equals(MatchOptions.MODIFIER_ANNOTATION_NAME)) {
          return;
        }

        for(PsiNameValuePair pair:annotation.getParameterList().getAttributes()) {
          final PsiAnnotationMemberValue value = pair.getValue();

          if (value instanceof PsiArrayInitializerMemberValue) {
            for(PsiAnnotationMemberValue v:((PsiArrayInitializerMemberValue)value).getInitializers()) {
              final String name = StringUtil.stripQuotesAroundValue(v.getText());
              checkModifier(name);
            }

          } else if (value != null) {
            final String name = StringUtil.stripQuotesAroundValue(value.getText());
            checkModifier(name);
          }
        }
      }

      private void checkModifier(final String name) {
        if (!MatchOptions.INSTANCE_MODIFIER_NAME.equals(name) &&
            !PsiModifier.PACKAGE_LOCAL.equals(name) &&
            Arrays.binarySearch(JavaMatchingVisitor.MODIFIERS, name) < 0
          ) {
          throw new MalformedPatternException(SSRBundle.message("invalid.modifier.type",name));
        }
      }

      @Override
      public void visitErrorElement(PsiErrorElement element) {
        super.visitErrorElement(element);
        //final PsiElement parent = element.getParent();
        //if (parent != myCurrent || !"';' expected".equals(element.getErrorDescription())) {
        //  throw new MalformedPatternException(element.getErrorDescription());
        //}
      }

      public void setCurrent(PsiElement current) {
        myCurrent = current;
      }
    }
    ValidatingVisitor visitor = new ValidatingVisitor();
    final CompiledPattern compiledPattern = PatternCompiler.compilePattern(project, options);
    final int nodeCount = compiledPattern.getNodeCount();
    final NodeIterator nodes = compiledPattern.getNodes();
    while (nodes.hasNext()) {
      final PsiElement current = nodes.current();
      visitor.setCurrent(nodeCount == 1 && current instanceof PsiExpressionStatement ? current : null);
      current.accept(visitor);
      nodes.advance();
    }
    nodes.reset();
  }

  @Override
  public void checkReplacementPattern(Project project, ReplaceOptions options) {
    MatchOptions matchOptions = options.getMatchOptions();
    FileType fileType = matchOptions.getFileType();
    PsiElement[] statements = MatcherImplUtil.createTreeFromText(
      matchOptions.getSearchPattern(),
      PatternTreeContext.Block,
      fileType,
      project
    );
    final boolean searchIsExpression = statements.length == 1 && statements[0].getLastChild() instanceof PsiErrorElement;

    PsiElement[] statements2 = MatcherImplUtil.createTreeFromText(
      options.getReplacement(),
      PatternTreeContext.Block,
      fileType,
      project
    );
    final boolean replaceIsExpression = statements2.length == 1 && statements2[0].getLastChild() instanceof PsiErrorElement;

    if (searchIsExpression != replaceIsExpression) {
      throw new UnsupportedPatternException(
        searchIsExpression ? SSRBundle.message("replacement.template.is.not.expression.error.message") :
        SSRBundle.message("search.template.is.not.expression.error.message")
      );
    }
  }

  @Override
  public LanguageFileType getDefaultFileType(LanguageFileType currentDefaultFileType) {
    return StdFileTypes.JAVA;
  }

  @Override
  Configuration[] getPredefinedTemplates() {
    return JavaPredefinedConfigurations.createPredefinedTemplates();
  }

  @Override
  public void provideAdditionalReplaceOptions(@NotNull PsiElement node, final ReplaceOptions options, final ReplacementBuilder builder) {
    final String templateText = TemplateManager.getInstance(node.getProject()).createTemplate("", "", options.getReplacement()).getTemplateText();
    node.accept(new JavaRecursiveElementWalkingVisitor() {
      @Override
      public void visitReferenceExpression(PsiReferenceExpression expression) {
        visitElement(expression);
      }

      @Override
      public void visitVariable(PsiVariable field) {
        super.visitVariable(field);

        final PsiExpression initializer = field.getInitializer();

        if (initializer != null) {
          final String initText = initializer.getText();

          if (StructuralSearchUtil.isTypedVariable(initText)) {
            final ParameterInfo initInfo = builder.findParameterization(Replacer.stripTypedVariableDecoration(initText));

            if (initInfo != null) {
              initInfo.setVariableInitializerContext(true);
            }
          }
        }
      }

      @Override
      public void visitClass(PsiClass aClass) {
        super.visitClass(aClass);

        MatchVariableConstraint constraint =
          options.getMatchOptions().getVariableConstraint(CompiledPattern.ALL_CLASS_UNMATCHED_CONTENT_VAR_ARTIFICIAL_NAME);
        if (constraint != null) {
          ParameterInfo e = new ParameterInfo();
          e.setName(CompiledPattern.ALL_CLASS_UNMATCHED_CONTENT_VAR_ARTIFICIAL_NAME);
          e.setStartIndex(templateText.lastIndexOf('}'));
          builder.addParametrization(e);
        }
      }

      @Override
      public void visitParameter(PsiParameter parameter) {
        super.visitParameter(parameter);

        String name = parameter.getName();
        String type = parameter.getType().getCanonicalText();

        if (StructuralSearchUtil.isTypedVariable(name)) {
          name = Replacer.stripTypedVariableDecoration(name);

          if (StructuralSearchUtil.isTypedVariable(type)) {
            type = Replacer.stripTypedVariableDecoration(type);
          }
          ParameterInfo nameInfo = builder.findParameterization(name);
          ParameterInfo typeInfo = builder.findParameterization(type);

          if (nameInfo != null && typeInfo != null && !(parameter.getParent() instanceof PsiCatchSection)) {
            nameInfo.setArgumentContext(false);
            typeInfo.setArgumentContext(false);
            typeInfo.setMethodParameterContext(true);
            nameInfo.setMethodParameterContext(true);
            typeInfo.setElement(parameter.getTypeElement());
          }
        }
      }
    });
  }

  @Override
  public int handleSubstitution(final ParameterInfo info,
                                MatchResult match,
                                StringBuilder result,
                                int offset,
                                HashMap<String, MatchResult> matchMap) {
    if (info.getName().equals(match.getName())) {
      String replacementString = match.getMatchImage();
      boolean forceAddingNewLine = false;

      if (info.isMethodParameterContext()) {
        StringBuilder buf = new StringBuilder();
        handleMethodParameter(buf, info, matchMap);
        replacementString = buf.toString();
      }
      else if (match.getAllSons().size() > 0 && !match.isScopeMatch()) {
        // compound matches
        StringBuilder buf = new StringBuilder();
        MatchResult r = null;

        for (final MatchResult matchResult : match.getAllSons()) {
          MatchResult previous = r;
          r = matchResult;

          final PsiElement currentElement = r.getMatch();

          if (buf.length() > 0) {
            final PsiElement parent = currentElement.getParent();
            if (info.isStatementContext()) {
              final PsiElement previousElement = previous.getMatchRef().getElement();

              if (!(previousElement instanceof PsiComment) &&
                  ( buf.charAt(buf.length() - 1) != '}' ||
                    previousElement instanceof PsiDeclarationStatement
                  )
                ) {
                buf.append(';');
              }

              final PsiElement prevSibling = currentElement.getPrevSibling();

              if (prevSibling instanceof PsiWhiteSpace &&
                  prevSibling.getPrevSibling() == previous.getMatch()
                ) {
                // consequent statements matched so preserve whitespacing
                buf.append(prevSibling.getText());
              }
              else {
                buf.append('\n');
              }
            }
            else if (info.isArgumentContext()) {
              buf.append(',');
            }
            else if (parent instanceof PsiClass) {
              final PsiElement prevSibling = PsiTreeUtil.skipSiblingsBackward(currentElement, PsiWhiteSpace.class);
              if (prevSibling instanceof PsiJavaToken && JavaTokenType.COMMA.equals(((PsiJavaToken)prevSibling).getTokenType())) {
                buf.append(',');
              }
              else {
                buf.append('\n');
              }
            }
            else if (parent instanceof PsiReferenceList) {
              buf.append(',');
            }
            else {
              buf.append(' ');
            }
          }

          buf.append(r.getMatchImage());
          removeExtraSemicolonForSingleVarInstanceInMultipleMatch(info, r, buf);
          forceAddingNewLine = currentElement instanceof PsiComment;
        }

        replacementString = buf.toString();
      } else {
        StringBuilder buf = new StringBuilder();
        if (info.isStatementContext()) {
          forceAddingNewLine = match.getMatch() instanceof PsiComment;
        }
        buf.append(replacementString);
        removeExtraSemicolonForSingleVarInstanceInMultipleMatch(info, match, buf);
        replacementString = buf.toString();
      }

      offset = Replacer.insertSubstitution(result, offset, info, replacementString);
      offset = removeExtraSemicolon(info, offset, result, match);
      if (forceAddingNewLine && info.isStatementContext()) {
        result.insert(info.getStartIndex() + offset + 1, '\n');
        offset ++;
      }
    }
    return offset;
  }

  @Override
  public int processAdditionalOptions(ParameterInfo info, int offset, StringBuilder result, MatchResult r) {
    if (info.isStatementContext()) {
      return removeExtraSemicolon(info, offset, result, r);
    }
    return offset;
  }

  @Override
  public boolean isIdentifier(PsiElement element) {
    return element instanceof PsiIdentifier;
  }

  @Override
  public Collection<String> getReservedWords() {
    return Collections.singleton(PsiModifier.PACKAGE_LOCAL);
  }

  @Override
  public boolean isDocCommentOwner(PsiElement match) {
    return match instanceof PsiMember;
  }

  private static void handleMethodParameter(StringBuilder buf, ParameterInfo info, HashMap<String, MatchResult> matchMap) {
    if(info.getElement() ==null) {
      // no specific handling for name of method parameter since it is handled with type
      return;
    }

    String name = ((PsiParameter)info.getElement().getParent()).getName();
    name = StructuralSearchUtil.isTypedVariable(name) ? Replacer.stripTypedVariableDecoration(name):name;

    final MatchResult matchResult = matchMap.get(name);
    if (matchResult == null) return;

    if (matchResult.isMultipleMatch()) {
      for (MatchResult result : matchResult.getAllSons()) {
        if (buf.length() > 0) {
          buf.append(',');
        }

        appendParameter(buf, result);
      }
    } else {
      appendParameter(buf, matchResult);
    }
  }

  private static void appendParameter(final StringBuilder buf, final MatchResult _matchResult) {
    for(Iterator<MatchResult> j = _matchResult.getAllSons().iterator();j.hasNext();) {
      buf.append(j.next().getMatchImage()).append(' ').append(j.next().getMatchImage());
    }
  }

  private static void removeExtraSemicolonForSingleVarInstanceInMultipleMatch(final ParameterInfo info, MatchResult r, StringBuilder buf) {
    if (info.isStatementContext()) {
      final PsiElement element = r.getMatchRef().getElement();

      // remove extra ;
      if (buf.charAt(buf.length()-1)==';' &&
          r.getMatchImage().charAt(r.getMatchImage().length()-1)==';' &&
          ( element instanceof PsiReturnStatement ||
            element instanceof PsiDeclarationStatement ||
            element instanceof PsiExpressionStatement ||
            element instanceof PsiAssertStatement ||
            element instanceof PsiBreakStatement ||
            element instanceof PsiContinueStatement ||
            element instanceof PsiMember ||
            element instanceof PsiIfStatement && !(((PsiIfStatement)element).getThenBranch() instanceof PsiBlockStatement) ||
            element instanceof PsiLoopStatement && !(((PsiLoopStatement)element).getBody() instanceof PsiBlockStatement)
          )
        ) {
        // contains extra ;
        buf.deleteCharAt(buf.length()-1);
      }
    }
  }

  private static int removeExtraSemicolon(ParameterInfo info, int offset, StringBuilder result, MatchResult match) {
    if (info.isStatementContext()) {
      int index = offset+ info.getStartIndex();
      if (result.charAt(index)==';' &&
          ( match == null ||
            ( result.charAt(index-1)=='}' &&
              !(match.getMatch() instanceof PsiDeclarationStatement) && // array init in dcl
              !(match.getMatch() instanceof PsiNewExpression) // array initializer
            ) ||
            ( !match.isMultipleMatch() &&                                                // ; in comment
              match.getMatch() instanceof PsiComment
            ) ||
            ( match.isMultipleMatch() &&                                                 // ; in comment
              match.getAllSons().get( match.getAllSons().size() - 1 ).getMatch() instanceof PsiComment
            )
          )
        ) {
        result.deleteCharAt(index);
        --offset;
      }
    }

    return offset;
  }
}
