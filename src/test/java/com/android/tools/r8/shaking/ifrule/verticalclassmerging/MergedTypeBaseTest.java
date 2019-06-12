// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking.ifrule.verticalclassmerging;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public abstract class MergedTypeBaseTest extends TestBase {

  static class A {}

  static class B extends A {}

  static class C {}

  interface I {}

  interface J extends I {}

  interface K {}

  static class Unused {}

  private final TestParameters parameters;
  final List<Class<?>> classes;
  final boolean enableVerticalClassMerging;

  public MergedTypeBaseTest(TestParameters parameters, boolean enableVerticalClassMerging) {
    this(parameters, enableVerticalClassMerging, ImmutableList.of());
  }

  public MergedTypeBaseTest(
      TestParameters parameters,
      boolean enableVerticalClassMerging,
      List<Class<?>> additionalClasses) {
    this.parameters = parameters;
    this.enableVerticalClassMerging = enableVerticalClassMerging;
    this.classes =
        ImmutableList.<Class<?>>builder()
            .add(A.class, B.class, C.class, I.class, J.class, K.class, Unused.class, getTestClass())
            .addAll(additionalClasses)
            .build();
  }

  @Parameters(name = "{0}, vertical class merging: {1}")
  public static Collection<Object[]> data() {
    // We don't run this on Proguard, as Proguard does not merge A into B.
    return buildParameters(
        getTestParameters().withAllRuntimes().build(), BooleanUtils.values());
  }

  public abstract Class<?> getTestClass();

  public String getAdditionalKeepRules() {
    return "";
  }

  public abstract String getConditionForProguardIfRule();

  public abstract String getExpectedStdout();

  public void inspect(CodeInspector inspector) {
    assertThat(inspector.clazz(Unused.class), isPresent());

    // Verify that A and I are no longer present when vertical class merging is enabled.
    if (enableVerticalClassMerging) {
      assertThat(inspector.clazz(A.class), not(isPresent()));
      assertThat(inspector.clazz(I.class), not(isPresent()));
    }
  }

  @Test
  public void testIfRule() throws Exception {
    String expected = getExpectedStdout();
    assertEquals(expected, runOnJava(getTestClass()));

    testForR8(parameters.getBackend())
        .addProgramClasses(classes)
        .addKeepMainRule(getTestClass())
        .addKeepRules(
            getConditionForProguardIfRule(),
            "-keep class " + Unused.class.getTypeName(),
            getAdditionalKeepRules())
        .noMinification()
        .addOptionsModification(this::configure)
        .enableClassInliningAnnotations()
        .enableSideEffectAnnotations()
        .setMinApi(parameters.getRuntime())
        .run(parameters.getRuntime(), getTestClass())
        .assertSuccessWithOutput(expected)
        .inspect(this::inspect);
  }

  public void configure(InternalOptions options) {
    options.enableVerticalClassMerging = enableVerticalClassMerging;

    // To ensure that the handling of extends and implements rules work as intended,
    // and that we don't end up keeping `Unused` only because one of the two implementations work.
    options.testing.allowProguardRulesThatUseExtendsOrImplementsWrong = false;
  }
}
