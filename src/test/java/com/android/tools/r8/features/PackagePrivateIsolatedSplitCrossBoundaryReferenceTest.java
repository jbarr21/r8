// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.features;

import static com.android.tools.r8.DiagnosticsMatcher.diagnosticMessage;
import static com.android.tools.r8.DiagnosticsMatcher.diagnosticType;
import static com.android.tools.r8.utils.codeinspector.AssertUtils.assertFailsCompilationIf;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.equalTo;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestCompilerBuilder.DiagnosticsConsumer;
import com.android.tools.r8.TestDiagnosticMessages;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.features.diagnostic.IllegalAccessWithIsolatedFeatureSplitsDiagnostic;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.MethodReferenceUtils;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class PackagePrivateIsolatedSplitCrossBoundaryReferenceTest extends TestBase {

  @Parameter(0)
  public boolean enableIsolatedSplits;

  @Parameter(1)
  public TestParameters parameters;

  @Parameters(name = "{1}, isolated splits: {0}")
  public static List<Object[]> data() {
    return buildParameters(
        BooleanUtils.values(), getTestParameters().withDexRuntimesAndAllApiLevels().build());
  }

  @Test
  public void testPublicToPublic() throws Exception {
    runTest("testPublicToPublic", TestDiagnosticMessages::assertNoMessages);
  }

  @Test
  public void testPublicToNonPublic() throws Exception {
    MethodReference accessedMethod =
        Reference.methodFromMethod(NonPublicBase.class.getDeclaredMethod("nonPublicMethod"));
    MethodReference context =
        Reference.methodFromMethod(Feature.class.getDeclaredMethod("testPublicToNonPublic"));
    assertFailsCompilationIf(
        enableIsolatedSplits,
        () ->
            runTest(
                "testPublicToNonPublic",
                diagnostics ->
                    diagnostics.assertErrorsMatch(
                        allOf(
                            diagnosticType(IllegalAccessWithIsolatedFeatureSplitsDiagnostic.class),
                            diagnosticMessage(
                                equalTo(getExpectedDiagnosticMessage(accessedMethod, context)))))));
  }

  @Test
  public void testNonPublicToPublic() throws Exception {
    ClassReference accessedClass = Reference.classFromClass(NonPublicBaseSub.class);
    MethodReference context =
        Reference.methodFromMethod(Feature.class.getDeclaredMethod("testNonPublicToPublic"));
    assertFailsCompilationIf(
        enableIsolatedSplits,
        () ->
            runTest(
                "testNonPublicToPublic",
                diagnostics ->
                    diagnostics.assertErrorsMatch(
                        allOf(
                            diagnosticType(IllegalAccessWithIsolatedFeatureSplitsDiagnostic.class),
                            diagnosticMessage(
                                equalTo(getExpectedDiagnosticMessage(accessedClass, context)))))));
  }

  private static String getExpectedDiagnosticMessage(
      ClassReference accessedClass, MethodReference context) {
    return "Unexpected illegal access to non-public class in another feature split "
        + "(accessed: "
        + accessedClass.getTypeName()
        + ", context: "
        + MethodReferenceUtils.toSourceString(context)
        + ").";
  }

  private static String getExpectedDiagnosticMessage(
      MethodReference accessedMethod, MethodReference context) {
    return "Unexpected illegal access to non-public method in another feature split "
        + "(accessed: "
        + MethodReferenceUtils.toSourceString(accessedMethod)
        + ", context: "
        + MethodReferenceUtils.toSourceString(context)
        + ").";
  }

  private void runTest(String name, DiagnosticsConsumer inspector) throws Exception {
    testForR8(parameters.getBackend())
        .addProgramClasses(NonPublicBase.class, PublicBaseSub.class, NonPublicBaseSub.class)
        .addKeepClassAndMembersRules(
            NonPublicBase.class, PublicBaseSub.class, NonPublicBaseSub.class)
        .addFeatureSplit(Feature.class)
        .addKeepRules("-keep class " + Feature.class.getTypeName() + " { void " + name + "(); }")
        .enableIsolatedSplits(enableIsolatedSplits)
        .setMinApi(parameters)
        .compileWithExpectedDiagnostics(enableIsolatedSplits ? inspector : this::assertNoMessages);
  }

  private void assertNoMessages(TestDiagnosticMessages diagnostics) {
    diagnostics.assertNoMessages();
  }

  static class NonPublicBase {

    public static void publicMethod() {
      // Intentionally empty.
    }

    static void nonPublicMethod() {
      // Intentionally empty.
    }
  }

  public static class PublicBaseSub extends NonPublicBase {}

  static class NonPublicBaseSub extends NonPublicBase {}

  public static class Feature {

    public static void testPublicToPublic() {
      PublicBaseSub.publicMethod();
    }

    public static void testPublicToNonPublic() {
      PublicBaseSub.nonPublicMethod();
    }

    public static void testNonPublicToPublic() {
      NonPublicBaseSub.publicMethod();
    }
  }
}
