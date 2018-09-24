// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.optimize;

import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.optimize.InvokeSingleTargetExtractor.InvokeKind;
import com.google.common.collect.Sets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class VisibilityBridgeRemover {
  private final AppInfoWithSubtyping appInfo;
  private final DexApplication application;
  private final Set<DexEncodedMethod> unneededVisibilityBridges = Sets.newIdentityHashSet();

  public VisibilityBridgeRemover(AppInfoWithSubtyping appInfo, DexApplication application) {
    this.appInfo = appInfo;
    this.application = application;
  }

  private void identifyBridgeMethod(DexEncodedMethod method) {
    MethodAccessFlags accessFlags = method.accessFlags;
    if (accessFlags.isBridge() && !accessFlags.isAbstract()) {
      InvokeSingleTargetExtractor targetExtractor =
          new InvokeSingleTargetExtractor(appInfo.dexItemFactory);
      method.getCode().registerCodeReferences(targetExtractor);
      DexMethod target = targetExtractor.getTarget();
      InvokeKind kind = targetExtractor.getKind();
      // javac-generated visibility forward bridge method has same descriptor (name, signature and
      // return type).
      if (target != null && target.hasSameProtoAndName(method.method)) {
        assert !accessFlags.isPrivate() && !accessFlags.isConstructor();
        if (kind == InvokeKind.SUPER) {
          // This is a visibility forward, so check for the direct target.
          DexEncodedMethod targetMethod =
              appInfo.resolveMethod(target.getHolder(), target).asSingleTarget();
          if (targetMethod != null && targetMethod.accessFlags.isPublic()) {
            if (Log.ENABLED) {
              Log.info(getClass(), "Removing visibility forwarding %s -> %s", method.method,
                  targetMethod.method);
            }
            unneededVisibilityBridges.add(method);
          }
        }
      }
    }
  }

  private void removeUnneededVisibilityBridges() {
    Set<DexType> classes = unneededVisibilityBridges.stream()
        .map(method -> method.method.getHolder())
        .collect(Collectors.toSet());
    for (DexType type : classes) {
      DexClass clazz = appInfo.definitionFor(type);
      clazz.setVirtualMethods(removeMethods(clazz.virtualMethods(), unneededVisibilityBridges));
    }
  }

  private DexEncodedMethod[] removeMethods(DexEncodedMethod[] methods,
      Set<DexEncodedMethod> removals) {
    assert methods != null;
    List<DexEncodedMethod> newMethods = Arrays.stream(methods)
        .filter(method -> !removals.contains(method))
        .collect(Collectors.toList());
    assert newMethods.size() < methods.length;
    return newMethods.toArray(new DexEncodedMethod[newMethods.size()]);
  }

  public DexApplication run() {
    for (DexClass clazz : appInfo.classes()) {
      clazz.forEachMethod(this::identifyBridgeMethod);
    }
    if (!unneededVisibilityBridges.isEmpty()) {
      removeUnneededVisibilityBridges();
    }
    return application;
  }

}
