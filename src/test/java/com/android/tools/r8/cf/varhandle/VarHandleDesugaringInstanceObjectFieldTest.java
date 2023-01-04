// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.cf.varhandle;

import com.android.tools.r8.examples.jdk9.VarHandle;
import com.android.tools.r8.utils.StringUtils;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class VarHandleDesugaringInstanceObjectFieldTest extends VarHandleDesugaringTestBase {

  private static final String TEST_GET_SET_EXPECTED_OUTPUT =
      StringUtils.lines(
          "null", "A(1)", "true", "A(2)", "true", "1", "1", "true", "true", "2", "2", "true",
          "true", "3", "3", "true", "true", "4", "4", "true", "true", "5", "5", "true", "true", "6",
          "6", "true", "true", "7", "7", "true", "true", "8", "8", "true", "true", "9.0", "9.0",
          "true", "true", "10.0", "10.0", "true", "true", "11.0", "11.0", "true", "true", "12.0",
          "12.0", "true", "true", "A", "A", "true", "true", "B", "B", "true", "true");

  private static final String TEST_COMPAREANDSET_EXPECTED_OUTPUT =
      StringUtils.lines(
          "null", "A(1)", "true", "A(2)", "true", "1", "2", "3", "4", "4", "4", "4", "5", "6", "7",
          "8", "8", "8", "8", "false", "8", "false", "8", "8", "8", "8", "8", "8", "8", "8", "8",
          "8", "8", "8", "8", "8");

  private static final String EXPECTED_OUTPUT =
      "testSetGet\n"
          + TEST_GET_SET_EXPECTED_OUTPUT
          + "testSetVolatileGetVolatile\n"
          + TEST_GET_SET_EXPECTED_OUTPUT
          + "testSetReleaseGet\n"
          + TEST_GET_SET_EXPECTED_OUTPUT
          + "testCompareAndSet\n"
          + TEST_COMPAREANDSET_EXPECTED_OUTPUT
          + "testWeakCompareAndSet\n"
          + TEST_COMPAREANDSET_EXPECTED_OUTPUT
          + StringUtils.lines("testReturnValueClassCastException");

  private static final String MAIN_CLASS = VarHandle.InstanceObjectField.typeName();
  private static final List<String> JAR_ENTRIES =
      ImmutableList.of(
          "varhandle/InstanceObjectField.class", "varhandle/InstanceObjectField$A.class");

  @Override
  protected String getMainClass() {
    return MAIN_CLASS;
  }

  @Override
  protected List<String> getKeepRules() {
    return ImmutableList.of("-keep class " + getMainClass() + "{ <fields>; }");
  }

  @Override
  protected List<String> getJarEntries() {
    return JAR_ENTRIES;
  }

  @Override
  protected String getExpectedOutputForReferenceImplementation() {
    return StringUtils.lines(
        EXPECTED_OUTPUT.trim(), "Reference implementation", "Reference implementation");
  }

  @Override
  protected String getExpectedOutputForArtImplementation() {
    assert parameters.isDexRuntime();
    return StringUtils.lines(EXPECTED_OUTPUT.trim(), "Art implementation", "Art implementation");
  }
}
