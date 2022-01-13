// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.desugar.twr;

import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaring;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaringCollection;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaringEventConsumer;
import com.android.tools.r8.ir.desugar.FreshLocalProvider;
import com.android.tools.r8.ir.desugar.LocalStackAllocator;
import com.android.tools.r8.ir.desugar.backports.BackportedMethods;
import com.android.tools.r8.synthesis.SyntheticNaming.SyntheticKind;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import org.objectweb.asm.Opcodes;

public class TwrInstructionDesugaring implements CfInstructionDesugaring {

  private final AppView<?> appView;
  private final DexItemFactory dexItemFactory;
  private final DexProto twrCloseResourceProto;
  private final DexMethod addSuppressed;
  private final DexMethod getSuppressed;

  public TwrInstructionDesugaring(AppView<?> appView) {
    this.appView = appView;
    this.dexItemFactory = appView.dexItemFactory();
    this.twrCloseResourceProto =
        dexItemFactory.createProto(
            dexItemFactory.voidType, dexItemFactory.throwableType, dexItemFactory.objectType);
    this.addSuppressed = dexItemFactory.throwableMethods.addSuppressed;
    this.getSuppressed = dexItemFactory.throwableMethods.getSuppressed;
  }

  @Override
  public Collection<CfInstruction> desugarInstruction(
      CfInstruction instruction,
      FreshLocalProvider freshLocalProvider,
      LocalStackAllocator localStackAllocator,
      CfInstructionDesugaringEventConsumer eventConsumer,
      ProgramMethod context,
      MethodProcessingContext methodProcessingContext,
      CfInstructionDesugaringCollection desugaringCollection,
      DexItemFactory dexItemFactory) {
    if (!instruction.isInvoke()) {
      return null;
    }
    if (isTwrCloseResourceInvoke(instruction)) {
      return rewriteTwrCloseResourceInvoke(eventConsumer, context, methodProcessingContext);
    }
    if (!appView.options().canUseSuppressedExceptions()) {
      if (isTwrSuppressedInvoke(instruction, addSuppressed)) {
        return rewriteTwrAddSuppressedInvoke(eventConsumer, methodProcessingContext);
      }
      if (isTwrSuppressedInvoke(instruction, getSuppressed)) {
        return rewriteTwrGetSuppressedInvoke(eventConsumer, methodProcessingContext);
      }
    }
    return null;
  }

  private Collection<CfInstruction> rewriteTwrAddSuppressedInvoke(
      CfInstructionDesugaringEventConsumer eventConsumer,
      MethodProcessingContext methodProcessingContext) {
    DexItemFactory factory = appView.dexItemFactory();
    DexProto proto =
        factory.createProto(factory.voidType, factory.throwableType, factory.throwableType);
    return createAndCallSyntheticMethod(
        SyntheticKind.BACKPORT,
        proto,
        BackportedMethods::ThrowableMethods_addSuppressed,
        methodProcessingContext,
        eventConsumer::acceptBackportedMethod,
        methodProcessingContext.getMethodContext());
  }

  private Collection<CfInstruction> rewriteTwrGetSuppressedInvoke(
      CfInstructionDesugaringEventConsumer eventConsumer,
      MethodProcessingContext methodProcessingContext) {
    DexItemFactory factory = appView.dexItemFactory();
    DexProto proto =
        factory.createProto(
            factory.createArrayType(1, factory.throwableType), factory.throwableType);
    return createAndCallSyntheticMethod(
        SyntheticKind.BACKPORT,
        proto,
        BackportedMethods::ThrowableMethods_getSuppressed,
        methodProcessingContext,
        eventConsumer::acceptBackportedMethod,
        methodProcessingContext.getMethodContext());
  }

  private ImmutableList<CfInstruction> rewriteTwrCloseResourceInvoke(
      CfInstructionDesugaringEventConsumer eventConsumer,
      ProgramMethod context,
      MethodProcessingContext methodProcessingContext) {
    // Synthesize a new method.
    return createAndCallSyntheticMethod(
        SyntheticKind.TWR_CLOSE_RESOURCE,
        twrCloseResourceProto,
        BackportedMethods::CloseResourceMethod_closeResourceImpl,
        methodProcessingContext,
        eventConsumer::acceptTwrCloseResourceMethod,
        context);
  }

  private ImmutableList<CfInstruction> createAndCallSyntheticMethod(
      SyntheticKind kind,
      DexProto proto,
      BiFunction<InternalOptions, DexMethod, CfCode> generator,
      MethodProcessingContext methodProcessingContext,
      BiConsumer<ProgramMethod, ProgramMethod> eventConsumerCallback,
      ProgramMethod context) {
    ProgramMethod method =
        appView
            .getSyntheticItems()
            .createMethod(
                kind,
                methodProcessingContext.createUniqueContext(),
                appView,
                builder ->
                    builder
                        // Will be traced by the enqueuer.
                        .disableAndroidApiLevelCheck()
                        .setProto(proto)
                        .setAccessFlags(MethodAccessFlags.createPublicStaticSynthetic())
                        .setCode(methodSig -> generator.apply(appView.options(), methodSig)));
    eventConsumerCallback.accept(method, context);
    return ImmutableList.of(new CfInvoke(Opcodes.INVOKESTATIC, method.getReference(), false));
  }

  @Override
  public boolean needsDesugaring(CfInstruction instruction, ProgramMethod context) {
    if (!instruction.isInvoke()) {
      return false;
    }
    return isTwrCloseResourceInvoke(instruction)
        || isTwrSuppressedInvoke(instruction, addSuppressed)
        || isTwrSuppressedInvoke(instruction, getSuppressed);
  }

  private boolean isTwrSuppressedInvoke(CfInstruction instruction, DexMethod suppressed) {
    return instruction.isInvoke()
        && matchesMethodOfThrowable(instruction.asInvoke().getMethod(), suppressed);
  }

  private boolean matchesMethodOfThrowable(DexMethod invoked, DexMethod expected) {
    return invoked.name == expected.name
        && invoked.proto == expected.proto
        && isSubtypeOfThrowable(invoked.holder);
  }

  private boolean isSubtypeOfThrowable(DexType type) {
    while (type != null && type != dexItemFactory.objectType) {
      if (type == dexItemFactory.throwableType) {
        return true;
      }
      DexClass dexClass = appView.definitionFor(type);
      if (dexClass == null) {
        throw new CompilationError(
            "Class or interface "
                + type.toSourceString()
                + " required for desugaring of try-with-resources is not found.");
      }
      type = dexClass.superType;
    }
    return false;
  }

  private boolean isTwrCloseResourceInvoke(CfInstruction instruction) {
    return instruction.isInvokeStatic()
        && isTwrCloseResourceMethod(instruction.asInvoke().getMethod());
  }

  private boolean isTwrCloseResourceMethod(DexMethod method) {
    return method.name == dexItemFactory.twrCloseResourceMethodName
        && method.proto == dexItemFactory.twrCloseResourceMethodProto;
  }
}
