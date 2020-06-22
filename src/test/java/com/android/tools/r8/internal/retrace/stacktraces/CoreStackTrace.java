// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.internal.retrace.stacktraces;

public class CoreStackTrace extends RetraceInternalStackTraceForTest {

  public CoreStackTrace() {
    super("core_obfuscated.txt", "core_deobfuscated.txt");
  }
}
