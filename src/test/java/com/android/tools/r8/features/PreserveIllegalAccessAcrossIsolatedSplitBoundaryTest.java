// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.features;

import static com.android.tools.r8.utils.codeinspector.CodeMatchers.invokesMethod;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPackagePrivate;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresentIf;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.Box;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class PreserveIllegalAccessAcrossIsolatedSplitBoundaryTest extends TestBase {

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
  public void test() throws Exception {
    Box<CodeInspector> baseInspectorBox = new Box<>();
    testForR8(parameters.getBackend())
        .addProgramClasses(Base.class)
        .addKeepClassRules(Base.class)
        .addFeatureSplit(Feature.class)
        .addKeepClassAndMembersRules(Feature.class)
        .applyIf(enableIsolatedSplits, testBuilder -> testBuilder.addDontWarn(Feature.class))
        .enableIsolatedSplits(enableIsolatedSplits)
        .enableInliningAnnotations()
        .setMinApi(parameters)
        .compile()
        .inspect(
            baseInspectorBox::set,
            featureInspector -> {
              CodeInspector baseInspector = baseInspectorBox.get();
              ClassSubject baseClassSubject = baseInspector.clazz(Base.class);
              assertThat(baseClassSubject, isPresent());

              MethodSubject nonPublicMethodSubject =
                  baseClassSubject.uniqueMethodWithOriginalName("nonPublicMethod");
              assertThat(nonPublicMethodSubject, isPresentIf(enableIsolatedSplits));
              if (enableIsolatedSplits) {
                assertThat(nonPublicMethodSubject, isPackagePrivate());
              }

              MethodSubject otherMethodSubject =
                  baseClassSubject.uniqueMethodWithOriginalName("otherMethod");
              assertThat(otherMethodSubject, isPresent());

              ClassSubject featureClassSubject = featureInspector.clazz(Feature.class);
              assertThat(featureClassSubject, isPresent());

              MethodSubject featureMethodSubject =
                  featureClassSubject.uniqueMethodWithOriginalName("test");
              assertThat(featureMethodSubject, isPresent());
              assertThat(
                  featureMethodSubject,
                  invokesMethod(
                      enableIsolatedSplits ? nonPublicMethodSubject : otherMethodSubject));
            });
  }

  public static class Base {

    static void nonPublicMethod() {
      otherMethod();
    }

    @NeverInline
    public static void otherMethod() {
      System.out.println("Hello, world!");
    }
  }

  public static class Feature {

    public static void test() {
      Base.nonPublicMethod();
    }
  }
}
