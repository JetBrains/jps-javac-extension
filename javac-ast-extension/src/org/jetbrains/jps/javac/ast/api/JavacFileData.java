// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.jps.javac.ast.api;

import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.io.*;
import java.util.*;

public final class JavacFileData {
  public static final String CUSTOM_DATA_PLUGIN_ID = "ast.reference.collector"; // fake plugin name to fit into customOutputData API
  public static final String CUSTOM_DATA_KIND = "JavacFileData";

  private static final byte CLASS_MARKER = 0;
  private static final byte METHOD_MARKER = 1;
  private static final byte FIELD_MARKER = 2;
  private static final byte FUN_EXPR_MARKER = 3;

  private final String myFilePath;
  private final Map<JavacRef, Integer> myRefs;
  private final List<JavacTypeCast> myCasts;
  private final List<JavacDef> myDefs;
  private final Set<JavacRef> myImplicitRefs;

  public JavacFileData(@NotNull String path,
                       @NotNull Map<JavacRef, Integer> refs,
                       @NotNull List<JavacTypeCast> casts,
                       @NotNull List<JavacDef> defs,
                       @NotNull Set<JavacRef> implicitRefs) {
    myFilePath = path;
    myRefs = refs;
    myCasts = casts;
    myDefs = defs;
    myImplicitRefs = implicitRefs;
  }

  @NotNull
  public String getFilePath() {
    return myFilePath;
  }

  @NotNull
  public Set<JavacRef> getImplicitToStringRefs() {
    return myImplicitRefs;
  }

  @NotNull
  public Map<JavacRef, Integer> getRefs() {
    return myRefs;
  }

  @NotNull
  public List<JavacTypeCast> getCasts() {
    return myCasts;
  }

  @NotNull
  public List<JavacDef> getDefs() {
    return myDefs;
  }

  @NotNull
  public byte[] asBytes() {
    final ByteArrayOutputStream os = new ByteArrayOutputStream();
    DataOutputStream stream = new DataOutputStream(os);
    try {
      stream.writeUTF(getFilePath());
      saveRefs(stream, getRefs());
      saveCasts(stream, getCasts());
      saveDefs(stream, getDefs());
      saveImplicitToString(stream, getImplicitToStringRefs());
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    return os.toByteArray();
  }

  @NotNull
  public static JavacFileData fromBytes(byte[] bytes) {
    @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
    final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    try {
      final String path = in.readUTF();
      final Map<JavacRef, Integer> refs = readRefs(in);
      final List<JavacTypeCast> casts = readCasts(in);
      final List<JavacDef> defs = readDefs(in);
      final Set<JavacRef> implicitRefs = readImplicitToString(in);
      return new JavacFileData(path, refs, casts, defs, implicitRefs);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void saveRefs(final DataOutput out, Map<JavacRef, Integer> refs) throws IOException {
    out.writeInt(refs.size());
    for (Map.Entry<JavacRef, Integer> entry : refs.entrySet()) {
      writeJavacRef(out, entry.getKey());
      out.writeInt(entry.getValue());
    }
  }

  private static Map<JavacRef, Integer> readRefs(final DataInput in) throws IOException {
    int size = in.readInt();
    Map<JavacRef, Integer> deserialized = new HashMap<JavacRef, Integer>(size, 0.95f);
    while (size-- > 0) {
      final JavacRef key = readJavacRef(in);
      final int value = in.readInt();
      deserialized.put(key, value);
    }
    return deserialized;
  }


  private static void saveDefs(final DataOutput out, List<? extends JavacDef> defs) throws IOException {
    out.writeInt(defs.size());
    for (JavacDef t : defs) {
      writeJavacDef(out, t);
    }
  }

  private static List<JavacDef> readDefs(final DataInput in) throws IOException {
    int size = in.readInt();
    List<JavacDef> result = new ArrayList<JavacDef>(size);
    while (size-- > 0) {
      result.add(readJavacDef(in));
    }
    return result;
  }

  private static JavacDef readJavacDef(@NotNull DataInput in) throws IOException {
    final byte marker = in.readByte();
    switch (marker) {
      case CLASS_MARKER:
        final int supersSize = in.readInt();
        JavacRef[] superClasses = new JavacRef[supersSize];
        for (int i = 0; i < supersSize; i++) {
          superClasses[i] = readJavacRef(in);
        }
        return new JavacDef.JavacClassDef(readJavacRef(in), superClasses);
      case FUN_EXPR_MARKER:
        return new JavacDef.JavacFunExprDef(readJavacRef(in));
      case METHOD_MARKER:
        JavacRef retType = readJavacRef(in);
        byte dimension = in.readByte();
        boolean isStatic = in.readBoolean();
        return new JavacDef.JavacMemberDef(readJavacRef(in), retType, dimension, isStatic);
      default:
        throw new IllegalStateException("unknown marker " + marker);
    }
  }

  private static void writeJavacDef(@NotNull DataOutput out, JavacDef def) throws IOException {
    if (def instanceof JavacDef.JavacClassDef) {
      out.writeByte(CLASS_MARKER);
      final JavacRef[] superClasses = ((JavacDef.JavacClassDef)def).getSuperClasses();
      out.writeInt(superClasses.length);
      for (JavacRef aClass : superClasses) {
        writeJavacRef(out, aClass);
      }
    }
    else if (def instanceof JavacDef.JavacFunExprDef) {
      out.writeByte(FUN_EXPR_MARKER);
    }
    else if (def instanceof JavacDef.JavacMemberDef) {
      out.writeByte(METHOD_MARKER);
      writeJavacRef(out, ((JavacDef.JavacMemberDef)def).getReturnType());
      out.writeByte(((JavacDef.JavacMemberDef)def).getIteratorKind());
      out.writeBoolean(((JavacDef.JavacMemberDef)def).isStatic());
    }
    else {
      throw new IllegalStateException("unknown type: " + def.getClass());
    }
    writeJavacRef(out, def.getDefinedElement());
  }

  private static void writeJavacRef(@NotNull DataOutput out, JavacRef ref) throws IOException {
    if (ref instanceof JavacRef.JavacClass) {
      out.writeByte(CLASS_MARKER);
      out.writeBoolean(((JavacRef.JavacClass)ref).isAnonymous());
    }
    else if (ref instanceof JavacRef.JavacField) {
      out.writeByte(FIELD_MARKER);
      final String containingClass = ((JavacRef.JavacField)ref).getContainingClass();
      out.writeUTF(containingClass == null? "" : containingClass);
      out.writeUTF(ref.getOwnerName());
      final String descriptor = ((JavacRef.JavacField)ref).getDescriptor();
      out.writeUTF(descriptor == null? "" : descriptor);
    }
    else if (ref instanceof JavacRef.JavacMethod) {
      out.writeByte(METHOD_MARKER);
      final String containingClass = ((JavacRef.JavacMethod)ref).getContainingClass();
      out.writeUTF(containingClass == null? "" : containingClass);
      out.writeUTF(ref.getOwnerName());
      out.write(((JavacRef.JavacMethod)ref).getParamCount());
    }
    else {
      throw new IllegalStateException("unknown type: " + ref.getClass());
    }
    writeModifiers(out, ref);
    out.writeUTF(ref.getName());
  }

  private static JavacRef readJavacRef(@NotNull DataInput in) throws IOException {
    final byte marker = in.readByte();
    switch (marker) {
      case CLASS_MARKER:
        final boolean isAnonymous = in.readBoolean();
        final ModifiersStruct classModifiers = readModifiers(in);
        final String className = in.readUTF();
        return new JavacRef.JavacClassImpl(isAnonymous, classModifiers.matched, classModifiers.unmatched, className);

      case METHOD_MARKER:
        final String methodContainingClass = in.readUTF();
        final String methodOwnerName = in.readUTF();
        final byte methodParamCount = in.readByte();
        final ModifiersStruct methodModifiers = readModifiers(in);
        final String methodName = in.readUTF();
        return new JavacRef.JavacMethodImpl(methodContainingClass, methodOwnerName, methodParamCount, methodModifiers.matched, methodModifiers.unmatched, methodName);

      case FIELD_MARKER:
        final String fieldContainingClass = in.readUTF();
        final String fieldOwnerName = in.readUTF();
        final String fieldDescriptor = in.readUTF();
        final ModifiersStruct fieldModifiers = readModifiers(in);
        final String fieldName = in.readUTF();
        return new JavacRef.JavacFieldImpl(fieldContainingClass, fieldOwnerName, fieldModifiers.matched, fieldModifiers.unmatched, fieldName, fieldDescriptor);

      default:
        throw new IllegalStateException("unknown marker " + marker);
    }
  }

  private static void writeModifiers(final DataOutput output, JavacRef ref) throws IOException {
    Set<Modifier> modifiers = ref.getModifiers();
    Set<String> unmatched = ref.getUnmatchedModifiers();
    output.writeInt(modifiers.size() + unmatched.size());
    for (Modifier modifier : modifiers) {
      output.writeUTF(modifier.name());
    }
    for (String modifierName : unmatched) {
      output.writeUTF(modifierName);
    }
  }

  private static ModifiersStruct readModifiers(final DataInput input) throws IOException {
    int size = input.readInt();
    List<Modifier> matched = null;
    List<String> unmatched = null;
    if (size > 0) {
      matched = new ArrayList<>(size);
      while (size-- > 0) {
        String name = input.readUTF();
        try {
          matched.add(Modifier.valueOf(name));
        }
        catch (IllegalArgumentException e) {
          (unmatched == null? (unmatched = new ArrayList<>()) : unmatched).add(name);
        }
      }
    }
    return new ModifiersStruct(matched, unmatched);
  }

  private static void saveCasts(@NotNull final DataOutput output, @NotNull List<? extends JavacTypeCast> casts) throws IOException {
    output.writeInt(casts.size());
    for (JavacTypeCast cast : casts) {
      writeJavacRef(output, cast.getOperandType());
      writeJavacRef(output, cast.getCastType());
    }
  }

  @NotNull
  private static List<JavacTypeCast> readCasts(@NotNull final DataInput input) throws IOException {
    int size = input.readInt();
    List<JavacTypeCast> result = new ArrayList<>(size);
    while (size-- > 0) {
      final JavacRef.JavacClass operandType = (JavacRef.JavacClass)readJavacRef(input);
      final JavacRef.JavacClass castType = (JavacRef.JavacClass)readJavacRef(input);
      result.add(new JavacTypeCast(operandType, castType));
    }
    return result;
  }

  @NotNull
  private static Set<JavacRef> readImplicitToString(@NotNull DataInputStream in) throws IOException {
    int size = ((DataInput)in).readInt();
    final Set<JavacRef> result = new HashSet<>(size);
    while (size-- > 0) {
      result.add(readJavacRef(in));
    }
    return result;
  }

  private static void saveImplicitToString(@NotNull DataOutputStream out, @NotNull Set<? extends JavacRef> refs) throws IOException {
    ((DataOutput)out).writeInt(refs.size());
    for (JavacRef ref : refs) {
      writeJavacRef(out, ref);
    }
  }

  private static final class ModifiersStruct {
    public final Set<Modifier> matched;
    public final Set<String> unmatched;

    public ModifiersStruct(Collection<Modifier> matched, Collection<String> unmatched) {
      this.matched = matched == null || matched.isEmpty()? Collections.<Modifier>emptySet() : EnumSet.copyOf(matched);
      this.unmatched = unmatched == null || unmatched.isEmpty()? Collections.<String>emptySet() : Collections.unmodifiableSet(new HashSet<>(unmatched));
    }
  }
}
