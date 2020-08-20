// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.jps.javac.ast.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class JavacNameTable {
  private final Elements myElements;
  private Name myAsterisk;
  private Name myInit;
  private TypeElement myStreamElement;
  private TypeElement myIteratorElement;
  private TypeElement myIterableElement;
  private TypeElement myStringElement;
  private final Map<Name, String> myParsedNameCache = new LinkedHashMap<Name, String>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Name, String> eldest) {
      return size() > 5000;
    }
  };

  public JavacNameTable(Elements elements) {
    myElements = elements;
  }

  @NotNull
  public String parseName(Name name) {
    String value = myParsedNameCache.get(name);
    if (value == null) {
      myParsedNameCache.put(name, value = name.toString());
    }
    return value;
  }

  @NotNull
  public String parseBinaryName(Element element) {
    return parseName(myElements.getBinaryName((TypeElement)element));
  }

  public boolean isAsterisk(Name name) {
    if (myAsterisk == null) {
      myAsterisk = myElements.getName("*");
    }
    return myAsterisk == name;
  }

  public boolean isInit(Name name) {
    if (myInit == null) {
      myInit = myElements.getName("<init>");
    }
    return myInit == name;
  }

  @Nullable("if the type is not loaded to javac name table")
  public TypeElement getStreamElement() {
    if (myStreamElement == null) {
      myStreamElement = myElements.getTypeElement("java.util.stream.Stream");
    }
    return myStreamElement;
  }

  @Nullable("if the type is not loaded to javac name table")
  public TypeElement getIteratorElement() {
    if (myIteratorElement == null) {
      myIteratorElement = myElements.getTypeElement("java.util.Iterator");
    }
    return myIteratorElement;
  }

  @Nullable("if the type is not loaded to javac name table")
  public TypeElement getIterableElement() {
    if (myIterableElement == null) {
      myIterableElement = myElements.getTypeElement("java.lang.Iterable");
    }
    return myIterableElement;
  }

  @NotNull
  public TypeElement getStringElement() {
    if (myStringElement == null) {
      myStringElement = myElements.getTypeElement("java.lang.String");
    }
    return myStringElement;
  }
}
