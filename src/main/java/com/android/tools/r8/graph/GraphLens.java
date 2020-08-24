// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.shaking.KeepInfoCollection;
import com.android.tools.r8.utils.SetUtils;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.objects.Object2BooleanArrayMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A GraphLens implements a virtual view on top of the graph, used to delay global rewrites until
 * later IR processing stages.
 *
 * <p>Valid remappings are limited to the following operations:
 *
 * <ul>
 *   <li>Mapping a classes type to one of the super/subtypes.
 *   <li>Renaming private methods/fields.
 *   <li>Moving methods/fields to a super/subclass.
 *   <li>Replacing method/field references by the same method/field on a super/subtype
 *   <li>Moved methods might require changed invocation type at the call site
 * </ul>
 *
 * Note that the latter two have to take visibility into account.
 */
public abstract class GraphLens {

  /**
   * Result of a method lookup in a GraphLens.
   *
   * <p>This provide the new target and the invoke type to use.
   */
  public static class GraphLensLookupResult {

    private final DexMethod method;
    private final Type type;

    public GraphLensLookupResult(DexMethod method, Type type) {
      this.method = method;
      this.type = type;
    }

    public DexMethod getMethod() {
      return method;
    }

    public Type getType() {
      return type;
    }
  }

  public static class Builder {

    protected Builder() {}

    protected final Map<DexType, DexType> typeMap = new IdentityHashMap<>();
    protected final Map<DexMethod, DexMethod> methodMap = new IdentityHashMap<>();
    protected final Map<DexField, DexField> fieldMap = new IdentityHashMap<>();

    protected final BiMap<DexField, DexField> originalFieldSignatures = HashBiMap.create();
    protected final BiMap<DexMethod, DexMethod> originalMethodSignatures = HashBiMap.create();

    public void map(DexType from, DexType to) {
      if (from == to) {
        return;
      }
      typeMap.put(from, to);
    }

    public void map(DexMethod from, DexMethod to) {
      if (from == to) {
        return;
      }
      methodMap.put(from, to);
    }

    public void map(DexField from, DexField to) {
      if (from == to) {
        return;
      }
      fieldMap.put(from, to);
    }

    public void move(DexMethod from, DexMethod to) {
      if (from == to) {
        return;
      }
      map(from, to);
      originalMethodSignatures.put(to, from);
    }

    public void move(DexField from, DexField to) {
      if (from == to) {
        return;
      }
      fieldMap.put(from, to);
      originalFieldSignatures.put(to, from);
    }

    public GraphLens build(DexItemFactory dexItemFactory) {
      return build(dexItemFactory, getIdentityLens());
    }

    public GraphLens build(DexItemFactory dexItemFactory, GraphLens previousLens) {
      if (typeMap.isEmpty() && methodMap.isEmpty() && fieldMap.isEmpty()) {
        return previousLens;
      }
      return new NestedGraphLens(
          typeMap,
          methodMap,
          fieldMap,
          originalFieldSignatures,
          originalMethodSignatures,
          previousLens,
          dexItemFactory);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public abstract DexType getOriginalType(DexType type);

  public abstract DexField getOriginalFieldSignature(DexField field);

  public abstract DexMethod getOriginalMethodSignature(DexMethod method);

  public abstract DexField getRenamedFieldSignature(DexField originalField);

  public final DexMember<?, ?> getRenamedMemberSignature(DexMember<?, ?> originalMember) {
    return originalMember.isDexField()
        ? getRenamedFieldSignature(originalMember.asDexField())
        : getRenamedMethodSignature(originalMember.asDexMethod());
  }

  public final DexMethod getRenamedMethodSignature(DexMethod originalMethod) {
    return getRenamedMethodSignature(originalMethod, null);
  }

  public abstract DexMethod getRenamedMethodSignature(DexMethod originalMethod, GraphLens applied);

  public DexEncodedMethod mapDexEncodedMethod(
      DexEncodedMethod originalEncodedMethod, DexDefinitionSupplier definitions) {
    return mapDexEncodedMethod(originalEncodedMethod, definitions, null);
  }

  public DexEncodedMethod mapDexEncodedMethod(
      DexEncodedMethod originalEncodedMethod,
      DexDefinitionSupplier definitions,
      GraphLens applied) {
    assert originalEncodedMethod != DexEncodedMethod.SENTINEL;
    DexMethod newMethod = getRenamedMethodSignature(originalEncodedMethod.method, applied);
    // Note that:
    // * Even if `newMethod` is the same as `originalEncodedMethod.method`, we still need to look it
    //   up, since `originalEncodedMethod` may be obsolete.
    // * We can't directly use AppInfo#definitionFor(DexMethod) since definitions may not be
    //   updated either yet.
    DexClass newHolder = definitions.definitionFor(newMethod.holder);
    assert newHolder != null;
    DexEncodedMethod newEncodedMethod = newHolder.lookupMethod(newMethod);
    assert newEncodedMethod != null;
    return newEncodedMethod;
  }

  public ProgramMethod mapProgramMethod(
      ProgramMethod oldMethod, DexDefinitionSupplier definitions) {
    DexMethod newMethod = getRenamedMethodSignature(oldMethod.getReference());
    DexProgramClass holder = definitions.definitionForHolder(newMethod).asProgramClass();
    return holder.lookupProgramMethod(newMethod);
  }

  public abstract DexType lookupType(DexType type);

  // This overload can be used when the graph lens is known to be context insensitive.
  public DexMethod lookupMethod(DexMethod method) {
    assert verifyIsContextFreeForMethod(method);
    return lookupMethod(method, null, null).getMethod();
  }

  public abstract GraphLensLookupResult lookupMethod(
      DexMethod method, DexMethod context, Type type);

  public abstract RewrittenPrototypeDescription lookupPrototypeChanges(DexMethod method);

  public abstract DexField lookupField(DexField field);

  public DexMethod lookupGetFieldForMethod(DexField field, DexMethod context) {
    return null;
  }

  public DexMethod lookupPutFieldForMethod(DexField field, DexMethod context) {
    return null;
  }

  public DexReference lookupReference(DexReference reference) {
    if (reference.isDexType()) {
      return lookupType(reference.asDexType());
    } else if (reference.isDexMethod()) {
      return lookupMethod(reference.asDexMethod());
    } else {
      assert reference.isDexField();
      return lookupField(reference.asDexField());
    }
  }

  // The method lookupMethod() maps a pair INVOKE=(method signature, invoke type) to a new pair
  // INVOKE'=(method signature', invoke type'). This mapping can be context sensitive, meaning that
  // the result INVOKE' depends on where the invocation INVOKE is in the program. This is, for
  // example, used by the vertical class merger to translate invoke-super instructions that hit
  // a method in the direct super class to invoke-direct instructions after class merging.
  //
  // This method can be used to determine if a graph lens is context sensitive. If a graph lens
  // is context insensitive, it is safe to invoke lookupMethod() without a context (or to pass null
  // as context). Trying to invoke a context sensitive graph lens without a context will lead to
  // an assertion error.
  public abstract boolean isContextFreeForMethods();

  public boolean verifyIsContextFreeForMethod(DexMethod method) {
    return isContextFreeForMethods();
  }

  public static GraphLens getIdentityLens() {
    return IdentityGraphLens.getInstance();
  }

  public boolean hasCodeRewritings() {
    return true;
  }

  public final boolean isIdentityLens() {
    return this == getIdentityLens();
  }

  public GraphLens withCodeRewritingsApplied() {
    if (hasCodeRewritings()) {
      return new ClearCodeRewritingGraphLens(this);
    }
    return this;
  }

  public <T extends DexDefinition> boolean assertDefinitionsNotModified(Iterable<T> definitions) {
    for (DexDefinition definition : definitions) {
      DexReference reference = definition.toReference();
      // We allow changes to bridge methods as these get retargeted even if they are kept.
      boolean isBridge =
          definition.isDexEncodedMethod() && definition.asDexEncodedMethod().accessFlags.isBridge();
      assert isBridge || lookupReference(reference) == reference;
    }
    return true;
  }

  public <T extends DexReference> boolean assertPinnedNotModified(KeepInfoCollection keepInfo) {
    List<DexReference> pinnedItems = new ArrayList<>();
    keepInfo.forEachPinnedType(pinnedItems::add);
    keepInfo.forEachPinnedMethod(pinnedItems::add);
    keepInfo.forEachPinnedField(pinnedItems::add);
    return assertReferencesNotModified(pinnedItems);
  }

  public <T extends DexReference> boolean assertReferencesNotModified(Iterable<T> references) {
    for (DexReference reference : references) {
      if (reference.isDexField()) {
        DexField field = reference.asDexField();
        assert getRenamedFieldSignature(field) == field;
      } else if (reference.isDexMethod()) {
        DexMethod method = reference.asDexMethod();
        assert getRenamedMethodSignature(method) == method;
      } else {
        assert reference.isDexType();
        DexType type = reference.asDexType();
        assert lookupType(type) == type;
      }
    }
    return true;
  }

  public DexReference rewriteReference(DexReference reference) {
    if (reference.isDexField()) {
      return getRenamedFieldSignature(reference.asDexField());
    }
    if (reference.isDexMethod()) {
      return getRenamedMethodSignature(reference.asDexMethod());
    }
    assert reference.isDexType();
    return lookupType(reference.asDexType());
  }

  public Set<DexReference> rewriteReferences(Set<DexReference> references) {
    Set<DexReference> result = SetUtils.newIdentityHashSet(references.size());
    for (DexReference reference : references) {
      result.add(rewriteReference(reference));
    }
    return result;
  }

  public <T> ImmutableMap<DexReference, T> rewriteReferenceKeys(Map<DexReference, T> map) {
    ImmutableMap.Builder<DexReference, T> builder = ImmutableMap.builder();
    map.forEach((reference, value) -> builder.put(rewriteReference(reference), value));
    return builder.build();
  }

  public Object2BooleanMap<DexReference> rewriteReferenceKeys(Object2BooleanMap<DexReference> map) {
    Object2BooleanMap<DexReference> result = new Object2BooleanArrayMap<>();
    for (Object2BooleanMap.Entry<DexReference> entry : map.object2BooleanEntrySet()) {
      result.put(rewriteReference(entry.getKey()), entry.getBooleanValue());
    }
    return result;
  }

  public ImmutableSortedSet<DexMethod> rewriteMethods(Set<DexMethod> methods) {
    ImmutableSortedSet.Builder<DexMethod> builder =
        new ImmutableSortedSet.Builder<>(PresortedComparable::slowCompare);
    for (DexMethod method : methods) {
      builder.add(getRenamedMethodSignature(method));
    }
    return builder.build();
  }

  public <T> ImmutableMap<DexField, T> rewriteFieldKeys(Map<DexField, T> map) {
    ImmutableMap.Builder<DexField, T> builder = ImmutableMap.builder();
    map.forEach((field, value) -> builder.put(getRenamedFieldSignature(field), value));
    return builder.build();
  }

  public ImmutableSet<DexType> rewriteTypes(Set<DexType> types) {
    ImmutableSortedSet.Builder<DexType> builder =
        new ImmutableSortedSet.Builder<>(PresortedComparable::slowCompare);
    for (DexType type : types) {
      builder.add(lookupType(type));
    }
    return builder.build();
  }

  public <T> ImmutableMap<DexType, T> rewriteTypeKeys(Map<DexType, T> map) {
    ImmutableMap.Builder<DexType, T> builder = ImmutableMap.builder();
    map.forEach((type, value) -> builder.put(lookupType(type), value));
    return builder.build();
  }

  public boolean verifyMappingToOriginalProgram(
      AppView<?> appView, DexApplication originalApplication) {
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    Iterable<DexProgramClass> classes = appView.appInfo().classesWithDeterministicOrder();
    // Collect all original fields and methods for efficient querying.
    Set<DexField> originalFields = Sets.newIdentityHashSet();
    Set<DexMethod> originalMethods = Sets.newIdentityHashSet();
    for (DexProgramClass clazz : originalApplication.classes()) {
      for (DexEncodedField field : clazz.fields()) {
        originalFields.add(field.field);
      }
      for (DexEncodedMethod method : clazz.methods()) {
        originalMethods.add(method.method);
      }
    }

    // Check that all fields and methods in the generated program can be mapped back to one of the
    // original fields or methods.
    for (DexProgramClass clazz : classes) {
      if (appView.appInfo().getSyntheticItems().isSyntheticClass(clazz)) {
        continue;
      }
      for (DexEncodedField field : clazz.fields()) {
        // The field $r8$clinitField may be synthesized by R8 in order to trigger the initialization
        // of the enclosing class. It is not present in the input, and therefore we do not require
        // that it can be mapped back to the original program.
        if (field.field.match(dexItemFactory.objectMembers.clinitField)) {
          continue;
        }
        DexField originalField = getOriginalFieldSignature(field.field);
        assert originalFields.contains(originalField)
            : "Unable to map field `" + field.field.toSourceString() + "` back to original program";
      }
      for (DexEncodedMethod method : clazz.methods()) {
        if (method.isD8R8Synthesized()) {
          // Methods synthesized by D8/R8 may not be mapped.
          continue;
        }
        DexMethod originalMethod = getOriginalMethodSignature(method.method);
        assert originalMethods.contains(originalMethod);
      }
    }

    return true;
  }

  private static class IdentityGraphLens extends GraphLens {

    private static IdentityGraphLens INSTANCE = new IdentityGraphLens();

    private IdentityGraphLens() {}

    private static IdentityGraphLens getInstance() {
      return INSTANCE;
    }

    @Override
    public DexType getOriginalType(DexType type) {
      return type;
    }

    @Override
    public DexField getOriginalFieldSignature(DexField field) {
      return field;
    }

    @Override
    public DexMethod getOriginalMethodSignature(DexMethod method) {
      return method;
    }

    @Override
    public DexField getRenamedFieldSignature(DexField originalField) {
      return originalField;
    }

    @Override
    public DexMethod getRenamedMethodSignature(DexMethod originalMethod, GraphLens applied) {
      return originalMethod;
    }

    @Override
    public DexType lookupType(DexType type) {
      return type;
    }

    @Override
    public GraphLensLookupResult lookupMethod(DexMethod method, DexMethod context, Type type) {
      return new GraphLensLookupResult(method, type);
    }

    @Override
    public RewrittenPrototypeDescription lookupPrototypeChanges(DexMethod method) {
      return RewrittenPrototypeDescription.none();
    }

    @Override
    public DexField lookupField(DexField field) {
      return field;
    }

    @Override
    public boolean isContextFreeForMethods() {
      return true;
    }

    @Override
    public boolean hasCodeRewritings() {
      return false;
    }
  }

  // This lens clears all code rewriting (lookup methods mimics identity lens behavior) but still
  // relies on the previous lens for names (getRenamed/Original methods).
  public static class ClearCodeRewritingGraphLens extends IdentityGraphLens {

    private final GraphLens previous;

    public ClearCodeRewritingGraphLens(GraphLens previous) {
      this.previous = previous;
    }

    @Override
    public DexType getOriginalType(DexType type) {
      return previous.getOriginalType(type);
    }

    @Override
    public DexField getOriginalFieldSignature(DexField field) {
      return previous.getOriginalFieldSignature(field);
    }

    @Override
    public DexMethod getOriginalMethodSignature(DexMethod method) {
      return previous.getOriginalMethodSignature(method);
    }

    @Override
    public DexField getRenamedFieldSignature(DexField originalField) {
      return previous.getRenamedFieldSignature(originalField);
    }

    @Override
    public DexMethod getRenamedMethodSignature(DexMethod originalMethod, GraphLens applied) {
      return this != applied
          ? previous.getRenamedMethodSignature(originalMethod, applied)
          : originalMethod;
    }

    @Override
    public DexType lookupType(DexType type) {
      return previous.lookupType(type);
    }
  }

  /**
   * GraphLens implementation with a parent lens using a simple mapping for type, method and field
   * mapping.
   *
   * <p>Subclasses can override the lookup methods.
   *
   * <p>For method mapping where invocation type can change just override {@link
   * #mapInvocationType(DexMethod, DexMethod, Type)} if the default name mapping applies, and only
   * invocation type might need to change.
   */
  public static class NestedGraphLens extends GraphLens {

    protected GraphLens previousLens;
    protected final DexItemFactory dexItemFactory;

    protected final Map<DexType, DexType> typeMap;
    private final Map<DexType, DexType> arrayTypeCache = new IdentityHashMap<>();
    protected final Map<DexMethod, DexMethod> methodMap;
    protected final Map<DexField, DexField> fieldMap;

    // Maps that store the original signature of fields and methods that have been affected, for
    // example, by vertical class merging. Needed to generate a correct Proguard map in the end.
    protected final BiMap<DexField, DexField> originalFieldSignatures;
    protected final BiMap<DexMethod, DexMethod> originalMethodSignatures;

    // Overrides this if the sub type needs to be a nested lens while it doesn't have any mappings
    // at all, e.g., publicizer lens that changes invocation type only.
    protected boolean isLegitimateToHaveEmptyMappings() {
      return false;
    }

    public NestedGraphLens(
        Map<DexType, DexType> typeMap,
        Map<DexMethod, DexMethod> methodMap,
        Map<DexField, DexField> fieldMap,
        BiMap<DexField, DexField> originalFieldSignatures,
        BiMap<DexMethod, DexMethod> originalMethodSignatures,
        GraphLens previousLens,
        DexItemFactory dexItemFactory) {
      assert !typeMap.isEmpty()
          || !methodMap.isEmpty()
          || !fieldMap.isEmpty()
          || isLegitimateToHaveEmptyMappings();
      this.typeMap = typeMap.isEmpty() ? null : typeMap;
      this.methodMap = methodMap;
      this.fieldMap = fieldMap;
      this.originalFieldSignatures = originalFieldSignatures;
      this.originalMethodSignatures = originalMethodSignatures;
      this.previousLens = previousLens;
      this.dexItemFactory = dexItemFactory;
    }

    public <T> T withAlternativeParentLens(GraphLens lens, Supplier<T> action) {
      GraphLens oldParent = previousLens;
      previousLens = lens;
      T result = action.get();
      previousLens = oldParent;
      return result;
    }

    @Override
    public DexType getOriginalType(DexType type) {
      return previousLens.getOriginalType(type);
    }

    @Override
    public DexField getOriginalFieldSignature(DexField field) {
      DexField originalField =
          originalFieldSignatures != null
              ? originalFieldSignatures.getOrDefault(field, field)
              : field;
      return previousLens.getOriginalFieldSignature(originalField);
    }

    @Override
    public DexMethod getOriginalMethodSignature(DexMethod method) {
      DexMethod originalMethod =
          originalMethodSignatures != null
              ? originalMethodSignatures.getOrDefault(method, method)
              : method;
      return previousLens.getOriginalMethodSignature(originalMethod);
    }

    @Override
    public DexField getRenamedFieldSignature(DexField originalField) {
      DexField renamedField = previousLens.getRenamedFieldSignature(originalField);
      return originalFieldSignatures != null
          ? originalFieldSignatures.inverse().getOrDefault(renamedField, renamedField)
          : renamedField;
    }

    @Override
    public DexMethod getRenamedMethodSignature(DexMethod originalMethod, GraphLens applied) {
      if (this == applied) {
        return originalMethod;
      }
      DexMethod renamedMethod = previousLens.getRenamedMethodSignature(originalMethod, applied);
      return originalMethodSignatures != null
          ? originalMethodSignatures.inverse().getOrDefault(renamedMethod, renamedMethod)
          : renamedMethod;
    }

    @Override
    public DexType lookupType(DexType type) {
      if (type.isArrayType()) {
        synchronized (this) {
          // This block need to be synchronized due to arrayTypeCache.
          DexType result = arrayTypeCache.get(type);
          if (result == null) {
            DexType baseType = type.toBaseType(dexItemFactory);
            DexType newType = lookupType(baseType);
            if (baseType == newType) {
              result = type;
            } else {
              result = type.replaceBaseType(newType, dexItemFactory);
            }
            arrayTypeCache.put(type, result);
          }
          return result;
        }
      }
      DexType previous = previousLens.lookupType(type);
      return typeMap != null ? typeMap.getOrDefault(previous, previous) : previous;
    }

    @Override
    public GraphLensLookupResult lookupMethod(DexMethod method, DexMethod context, Type type) {
      DexMethod previousContext =
          originalMethodSignatures != null
              ? originalMethodSignatures.getOrDefault(context, context)
              : context;
      GraphLensLookupResult previous = previousLens.lookupMethod(method, previousContext, type);
      DexMethod newMethod = methodMap.get(previous.getMethod());
      if (newMethod == null) {
        return previous;
      }
      // TODO(sgjesse): Should we always do interface to virtual mapping? Is it a performance win
      // that only subclasses which are known to need it actually do it?
      return new GraphLensLookupResult(
          newMethod, mapInvocationType(newMethod, method, previous.getType()));
    }

    @Override
    public RewrittenPrototypeDescription lookupPrototypeChanges(DexMethod method) {
      return previousLens.lookupPrototypeChanges(method);
    }

    @Override
    public DexMethod lookupGetFieldForMethod(DexField field, DexMethod context) {
      return previousLens.lookupGetFieldForMethod(field, context);
    }

    @Override
    public DexMethod lookupPutFieldForMethod(DexField field, DexMethod context) {
      return previousLens.lookupPutFieldForMethod(field, context);
    }

    /**
     * Default invocation type mapping.
     *
     * <p>This is an identity mapping. If a subclass need invocation type mapping either override
     * this method or {@link #lookupMethod(DexMethod, DexMethod, Type)}
     */
    protected Type mapInvocationType(DexMethod newMethod, DexMethod originalMethod, Type type) {
      return type;
    }

    /**
     * Standard mapping between interface and virtual invoke type.
     *
     * <p>Handle methods moved from interface to class or class to interface.
     */
    protected final Type mapVirtualInterfaceInvocationTypes(
        DexDefinitionSupplier definitions,
        DexMethod newMethod,
        DexMethod originalMethod,
        Type type) {
      if (type == Type.VIRTUAL || type == Type.INTERFACE) {
        // Get the invoke type of the actual definition.
        DexClass newTargetClass = definitions.definitionFor(newMethod.holder);
        if (newTargetClass == null) {
          return type;
        }
        DexClass originalTargetClass = definitions.definitionFor(originalMethod.holder);
        if (originalTargetClass != null
            && (originalTargetClass.isInterface() ^ (type == Type.INTERFACE))) {
          // The invoke was wrong to start with, so we keep it wrong. This is to ensure we get
          // the IncompatibleClassChangeError the original invoke would have triggered.
          return newTargetClass.accessFlags.isInterface() ? Type.VIRTUAL : Type.INTERFACE;
        }
        return newTargetClass.accessFlags.isInterface() ? Type.INTERFACE : Type.VIRTUAL;
      }
      return type;
    }

    @Override
    public DexField lookupField(DexField field) {
      DexField previous = previousLens.lookupField(field);
      return fieldMap.getOrDefault(previous, previous);
    }

    @Override
    public boolean isContextFreeForMethods() {
      return previousLens.isContextFreeForMethods();
    }

    @Override
    public boolean verifyIsContextFreeForMethod(DexMethod method) {
      assert previousLens.verifyIsContextFreeForMethod(method);
      return true;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      if (typeMap != null) {
        for (Map.Entry<DexType, DexType> entry : typeMap.entrySet()) {
          builder.append(entry.getKey().toSourceString()).append(" -> ");
          builder.append(entry.getValue().toSourceString()).append(System.lineSeparator());
        }
      }
      for (Map.Entry<DexMethod, DexMethod> entry : methodMap.entrySet()) {
        builder.append(entry.getKey().toSourceString()).append(" -> ");
        builder.append(entry.getValue().toSourceString()).append(System.lineSeparator());
      }
      for (Map.Entry<DexField, DexField> entry : fieldMap.entrySet()) {
        builder.append(entry.getKey().toSourceString()).append(" -> ");
        builder.append(entry.getValue().toSourceString()).append(System.lineSeparator());
      }
      builder.append(previousLens.toString());
      return builder.toString();
    }
  }
}
