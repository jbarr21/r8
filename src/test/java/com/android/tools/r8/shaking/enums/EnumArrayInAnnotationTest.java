// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking.enums;

import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestRuntime.CfVm;
import com.android.tools.r8.TestShrinkerBuilder;
import com.android.tools.r8.ToolHelper.DexVm;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.StringUtils;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class EnumArrayInAnnotationTest extends TestBase {

  @Parameter(0)
  public TestParameters parameters;

  @Parameter(1)
  public boolean useGenericEnumsRule;

  @Parameters(name = "{0}, use generic enums rule {1}")
  public static List<Object[]> data() {
    return buildParameters(
        getTestParameters().withAllRuntimesAndApiLevels().build(), BooleanUtils.values());
  }

  private static String EXPECTED_RESULT = StringUtils.lines("TEST_ONE", "TEST_TWO");

  @Test
  public void testRuntime() throws Exception {
    assumeTrue(useGenericEnumsRule);
    testForRuntime(parameters)
        .addInnerClasses(getClass())
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addKeepMainRule(Main.class)
        .applyIf(
            parameters.isCfRuntime() && useGenericEnumsRule,
            TestShrinkerBuilder::addKeepEnumsRule,
            parameters.isCfRuntime() && !useGenericEnumsRule,
            builder ->
                builder.addKeepRules(
                    "-keepclassmembernames enum "
                        + EnumArrayInAnnotationTest.Enum.class.getTypeName()
                        + " { <fields>; }"),
            builder -> {
              // Do nothing for DEX.
            })
        .addKeepRuntimeVisibleAnnotations()
        .run(parameters.getRuntime(), Main.class)
        .applyIf(
            parameters.isCfRuntime()
                && useGenericEnumsRule
                && parameters.asCfRuntime().isOlderThan(CfVm.JDK11),
            r -> r.assertFailureWithErrorThatThrows(ArrayStoreException.class),
            parameters.isCfRuntime()
                && useGenericEnumsRule
                && parameters.asCfRuntime().isNewerThanOrEqual(CfVm.JDK11),
            r -> r.assertFailureWithErrorThatThrows(EnumConstantNotPresentException.class),
            parameters.isDexRuntime()
                && parameters.asDexRuntime().getVm().isOlderThan(DexVm.ART_8_1_0_HOST),
            r -> r.assertFailureWithErrorThatThrows(ClassNotFoundException.class),
            r -> r.assertSuccessWithOutput(EXPECTED_RESULT));
  }

  public enum Enum {
    TEST_ONE,
    TEST_TWO
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE})
  public @interface MyAnnotation {

    Enum[] value();
  }

  @MyAnnotation(value = {Enum.TEST_ONE, Enum.TEST_TWO})
  public static class Main {

    public static void main(String[] args) {
      for (Enum enm : Main.class.getAnnotation(MyAnnotation.class).value()) {
        System.out.println(enm);
      }
    }
  }
}
