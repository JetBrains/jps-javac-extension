// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.jps.javac.ast;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.javac.ast.api.JavacDef;
import org.jetbrains.jps.javac.ast.api.JavacFileData;
import org.jetbrains.jps.javac.ast.api.JavacNameTable;
import org.jetbrains.jps.javac.ast.api.JavacRef;
import org.jetbrains.jps.javac.ast.api.JavacTypeCast;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaCompiler;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class JavacReferenceCollectorListener implements TaskListener {
  private static final String PACKAGE_INFO_SRC_FILENAME = "package-info.java";
  
  private final JavacReferenceCollector.Consumer<? super JavacFileData> myDataConsumer;
  private final JavacTask myJavacTask;
  private final JavacTreeRefScanner myAstScanner;
  private final boolean myAtLeastJdk8;

  private boolean myInitialized;
  private Elements myElementUtility;
  private Types myTypeUtility;
  private Trees myTreeUtility;
  private JavacNameTable myNameTableCache;

  private final Map<String, ReferenceCollector> myIncompletelyProcessedFiles = new HashMap<String, ReferenceCollector>(10);

  static void installOn(JavaCompiler.CompilationTask task, JavacReferenceCollector.Consumer<? super JavacFileData> dataConsumer) {
    JavacTask javacTask = (JavacTask)task;
    Method addTaskMethod; // jdk >= 8
    try {
      addTaskMethod = JavacTask.class.getMethod("addTaskListener", TaskListener.class);
    }
    catch (NoSuchMethodException e) {
      addTaskMethod = null;
    }
    final JavacReferenceCollectorListener taskListener = new JavacReferenceCollectorListener(
      dataConsumer, javacTask, addTaskMethod != null
    );
    if (addTaskMethod != null) {
      try {
        addTaskMethod.setAccessible(true);
        addTaskMethod.invoke(task, taskListener);
      }
      catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
      catch (InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    } else {
      // jdk 6-7
      javacTask.setTaskListener(taskListener);
    }
  }

  private JavacReferenceCollectorListener(JavacReferenceCollector.Consumer<? super JavacFileData> dataConsumer,
                                          JavacTask javacTask,
                                          boolean atLeastJdk8) {
    myDataConsumer = dataConsumer;
    myJavacTask = javacTask;
    myAtLeastJdk8 = atLeastJdk8;
    myAstScanner = createASTScanner();
  }

  private static JavacTreeRefScanner createASTScanner() {
    try {
      return (JavacTreeRefScanner)Class.forName("org.jetbrains.jps.javac.ast.Javac8RefScanner").newInstance();
    }
    catch (Throwable ignored) {
      return new JavacTreeRefScanner();
    }
  }

  @Override
  public void started(TaskEvent e) {

  }

  @Override
  public void finished(TaskEvent e) {
    // Should be initialized only when JavaCompiler was created (jdk 6-7).
    // Otherwise JavacReferenceCollectorListener will not be loaded to javac Context.
    initializeUtilitiesIfNeeded();
    if (e.getKind() == TaskEvent.Kind.ANALYZE) {
      // javac creates an event on each processed top level declared class not file
      final CompilationUnitTree unit = e.getCompilationUnit();
      final String fileName = new File(e.getSourceFile().toUri().getPath()).getPath();

      Tree declarationToProcess = myTreeUtility.getTree(e.getTypeElement());

      boolean collectImportsData;
      boolean addedToCache = true;
      ReferenceCollector incompletelyProcessedFile = myIncompletelyProcessedFiles.get(fileName);
      if (incompletelyProcessedFile == null) {
        final int declarationCount = unit.getTypeDecls().size();
        incompletelyProcessedFile = new ReferenceCollector(declarationCount, fileName, unit);
        if (declarationCount == 1 && declarationToProcess != null) {
          addedToCache = false;
        } else {
          myIncompletelyProcessedFiles.put(fileName, incompletelyProcessedFile);
        }
        collectImportsData = true;
      }
      else {
        collectImportsData = false;
      }

      final boolean isFileDataComplete;
      if (incompletelyProcessedFile.decrementRemainDeclarationsAndGet(declarationToProcess) == 0) {
        if (addedToCache) {
          myIncompletelyProcessedFiles.remove(fileName);
        }
        isFileDataComplete = true;
      }
      else {
        isFileDataComplete = false;
      }

      if (collectImportsData) {
        scanImports(unit, incompletelyProcessedFile.myFileData.getRefs(), incompletelyProcessedFile);
      }
      myAstScanner.scan(declarationToProcess, incompletelyProcessedFile);

      if (isFileDataComplete) {
        for (AnnotationTree annotation : unit.getPackageAnnotations()) {
          myAstScanner.scan(annotation, incompletelyProcessedFile);
        }

        myDataConsumer.consume(incompletelyProcessedFile.myFileData);
      }
    }
  }

  private void initializeUtilitiesIfNeeded() {
    if (!myInitialized) {
      myElementUtility = myJavacTask.getElements();
      myTypeUtility = myJavacTask.getTypes();
      myTreeUtility = Trees.instance(myJavacTask);
      myNameTableCache = new JavacNameTable(myElementUtility);
      myInitialized = true;
    }
  }

  private void scanImports(CompilationUnitTree compilationUnit, Map<JavacRef, Integer> elements, ReferenceCollector incompletelyProcessedFile) {
    for (ImportTree anImport : compilationUnit.getImports()) {
      final MemberSelectTree id = (MemberSelectTree)anImport.getQualifiedIdentifier();
      final Element element = incompletelyProcessedFile.getReferencedElement(id);
      if (element == null) {
        final Element ownerElement = incompletelyProcessedFile.getReferencedElement(id.getExpression());
        if (ownerElement == null) {
          continue; // unresolvable import
        }
        final Name name = id.getIdentifier();
        final JavacRef.ImportProperties importProps = JavacRef.ImportProperties.create(anImport.isStatic(), myNameTableCache.isAsterisk(name));
        if (ownerElement.getKind() == ElementKind.PACKAGE) {
          // package qualifiers occur in type-import-on-demand declarations only: import p.q.*;
          if (importProps.isOnDemand()) {
            incrementOrAdd(elements, new JavacRef.JavacPackageImportImpl(myNameTableCache.parseName(((PackageElement)ownerElement).getQualifiedName())));
          }
        }
        else {
          if (!importProps.isOnDemand()) {
            // member import
            for (Element memberElement : myElementUtility.getAllMembers((TypeElement)ownerElement)) {
              if (memberElement.getSimpleName() == name) {
                incrementOrAdd(elements, JavacRef.JavacElementRefBase.fromElement(null, memberElement, null, myNameTableCache, importProps));
              }
            }
          }
          // the qualifier type carries the import properties (including the on-demand flag for `import p.Outer.*`)
          incrementOrAdd(elements, JavacRef.JavacElementRefBase.fromElement(null, ownerElement, null, myNameTableCache, importProps));
          Element enclosing = ownerElement.getEnclosingElement();
          if (enclosing != null) {
            // enclosing types are referenced by the import statement, but are not the on-demand scope themselves
            collectClassImports(enclosing, elements, JavacRef.ImportProperties.create(importProps.isStatic(), false));
          }
        }
      }
      else {
        // class import
        collectClassImports(element, elements, JavacRef.ImportProperties.create(anImport.isStatic(), false));
      }
    }
  }

  private void collectClassImports(Element baseImport, Map<JavacRef, Integer> collector, final JavacRef.ImportProperties importProps) {
    for (Element element = baseImport;
         element != null && element.getKind() != ElementKind.PACKAGE;
         element = element.getEnclosingElement()) {
      incrementOrAdd(collector, JavacRef.JavacElementRefBase.fromElement(null, element, null, myNameTableCache, importProps));
    }
  }

  final class ReferenceCollector {
    private final JavacFileData myFileData;
    private final JavacTreeHelper myTreeHelper;
    private int myRemainDeclarations;
    private final JavacRef.JavacClass myPackageInfo;

    private ReferenceCollector(int remainDeclarations, String filePath, CompilationUnitTree unitTree) {
      myRemainDeclarations = remainDeclarations;
      myFileData = new JavacFileData(
        filePath, createReferenceHolder(), new ArrayList<JavacTypeCast>(), createDefinitionHolder(), new HashSet<JavacRef>()
      );
      myTreeHelper = new JavacTreeHelper(unitTree, myTreeUtility);

      if (isPackageInfo(filePath)) {
        final ExpressionTree packageName = unitTree.getPackageName();
        final String pack = packageName != null ? packageName.toString() : "";
        myPackageInfo = new JavacRef.JavacClassImpl(false, Collections.<Modifier>emptySet(), Collections.<String>emptySet(), pack.isEmpty()? "package-info" : pack + ".package-info");
        sinkDeclaration(new JavacDef.JavacClassDef(myPackageInfo, JavacRef.EMPTY_ARRAY));
      }
      else {
        myPackageInfo = null;
      }
    }

    private boolean isPackageInfo(String filePath) {
      if (filePath != null && filePath.endsWith(PACKAGE_INFO_SRC_FILENAME)) {
        final int idx = filePath.length() - PACKAGE_INFO_SRC_FILENAME.length() - 1;
        return idx >= 0 && (filePath.charAt(idx) == '/' || filePath.charAt(idx) == File.separatorChar);
      }
      return false;
    }

    void sinkReference(@Nullable JavacRef.JavacElementRefBase ref) {
      incrementOrAdd(myFileData.getRefs(), ref);
    }

    void sinkDeclaration(JavacDef def) {
     myFileData.getDefs().add(def);
    }

    void sinkImplicitToString(@Nullable JavacRef ref) {
      if (ref != null) {
        myFileData.getImplicitToStringRefs().add(ref);
      }
    }

    public void sinkTypeCast(JavacTypeCast typeCast) {
      myFileData.getCasts().add(typeCast);
    }

    @Nullable
    JavacRef.JavacElementRefBase asJavacRef(final Element containingClass, Element element) {
      return asJavacRef(containingClass, element, null);
    }

    @Nullable
    JavacRef.JavacElementRefBase asJavacRef(final Element containingClass, Element element, Element qualifier) {
      return JavacRef.JavacElementRefBase.fromElement(getContainingClassName(containingClass), element, qualifier, myNameTableCache);
    }

    @Nullable
    JavacRef.JavacElementRefBase asJavacRef(final Element containingClass, TypeMirror typeMirror) {
      final Element element = getTypeUtility().asElement(typeMirror);
      return element == null ? null : JavacRef.JavacElementRefBase.fromElement(getContainingClassName(containingClass), element, null, myNameTableCache);
    }

    private String getContainingClassName(Element containingClass) {
      return containingClass != null? myNameTableCache.parseBinaryName(containingClass) : myPackageInfo != null? myPackageInfo.getName() : null;
    }

    Element getReferencedElement(Tree tree) {
      return myTreeHelper.getReferencedElement(tree);
    }

    TypeMirror getType(Tree tree) {
      return myTreeHelper.getType(tree);
    }

    Types getTypeUtility() {
      return myTypeUtility;
    }

    JavacNameTable getNameTable() {
      return myNameTableCache;
    }

    long getStartOffset(Tree tree) {
      return myTreeHelper.getStartOffset(tree);
    }

    long getEndOffset(Tree tree) {
      return myTreeHelper.getEndOffset(tree);
    }

    private int decrementRemainDeclarationsAndGet(Tree declarationToProcess) {
      return declarationToProcess == null ? myRemainDeclarations : --myRemainDeclarations;
    }
  }

  private static Map<JavacRef, Integer> createReferenceHolder() {
    return new HashMap<JavacRef, Integer>(16, 0.95f);
  }

  private static List<JavacDef> createDefinitionHolder() {
    return new ArrayList<JavacDef>();
  }

  private final class JavacTreeHelper {
    private final TreePath myUnitPath;
    private final Trees myTreeUtil;
    private final SourcePositions myPositions;

    private JavacTreeHelper(CompilationUnitTree unit, Trees treeUtil) {
      myUnitPath = new TreePath(unit);
      myTreeUtil = treeUtil;
      myPositions = treeUtil.getSourcePositions();
    }

    private long getStartOffset(Tree tree) {
      return myPositions.getStartPosition(myUnitPath.getCompilationUnit(), tree);
    }

    private long getEndOffset(Tree tree) {
      return myPositions.getEndPosition(myUnitPath.getCompilationUnit(), tree);
    }

    private Element getReferencedElement(Tree tree) {
      final TreePath path = new TreePath(myUnitPath, tree);
      if (myAtLeastJdk8) {
        return myTreeUtil.getElement(path);
      } else {
        return getElementIfJdkUnder8(tree);
      }
    }

    private TypeMirror getType(Tree tree) {
      return myTreeUtil.getTypeMirror(new TreePath(myUnitPath, tree));
    }
  }

  private static void incrementOrAdd(Map<JavacRef, Integer> map, JavacRef key) {
    final Integer present = map.get(key);
    map.put(key, present != null? present + 1 : 1);
  }

  //TODO
  private static Element getElementIfJdkUnder8(Tree tree) {
    if (tree == null || tree instanceof PrimitiveTypeTree || tree instanceof ArrayTypeTree) return null;
    if (tree instanceof ParameterizedTypeTree) {
      return getElementIfJdkUnder8(((ParameterizedTypeTree)tree).getType());
    }
    Field symField;
    try {
      //should be the same to com.sun.tools.javac.tree.TreeInfo.symbolForImpl() since com.sun.source.util.Trees.getElement() works improperly under jdk 6-7
      symField = tree.getClass().getField(tree instanceof NewClassTree ? "constructor" : "sym");
    }
    catch (NoSuchFieldException e) {
      throw new RuntimeException(tree.getClass().getName());
    }
    try {
      return (Element) symField.get(tree);
    }
    catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
