// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.experimental.startup.profile.art;

import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.startup.ARTProfileClassRuleInfo;
import com.android.tools.r8.startup.ARTProfileMethodRuleInfo;

public interface ARTProfileBuilder {

  void addClassRule(ClassReference classReference, ARTProfileClassRuleInfo classRuleInfo);

  void addMethodRule(MethodReference methodReference, ARTProfileMethodRuleInfo methodRuleInfo);
}