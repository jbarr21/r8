// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.jasmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.debuginfo.DebugInfoInspector;
import com.android.tools.r8.jasmin.JasminBuilder.ClassFileVersion;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.StringUtils;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class InvalidDebugInfoTests extends JasminTestBase {

  // This is a regression test for invalid live-ranges of locals generated by some old Java
  // compilers. The issue is that a local slot may have been initialized outside the live-scope of
  // the variable and then the subsequent live-scope of the variable extends beyond its actual
  // liveness. In the example below the variable 'y' is initialized outside its range (it is thus
  // associated with the local 'x' (the SSA value is unaffected by the istore). Finally the 'return'
  // forces a read of all supposedly live variables before exiting. Here the attempt to read 'y'
  // will actually be a read of 'x'.
  @Test
  public void testInvalidInfoThrow() throws Exception {
    JasminBuilder builder = new JasminBuilder(ClassFileVersion.JSE_6);
    JasminBuilder.ClassBuilder clazz = builder.addClass("Test");

    clazz.addStaticMethod("foo", ImmutableList.of("I"), "V",
        ".limit stack 3",
        ".limit locals 4",
        ".var 0 is x I from LabelInit to LabelExit",
        ".var 1 is y I from LabelLocalStart to LabelExit",
        ".var 2 is e Ljava/lang/Exception; from LabelCatchStart to LabelCatchEnd",
        // var 3 is the jsr address
        "LabelInit:",
        "LabelTryStart:",
        "  ldc 84",
        "  iload 0",
        "  dup",
        "  istore 1", // init local[1] to value of local[0] (eg, 'x' since 'y' is not live yet).
        "  idiv",
        "  istore 0",
        "LabelLocalStart:",
        "  jsr LabelPrint",
        "  goto LabelExit",
        "LabelTryEnd:",
        "LabelCatchStart:",
        "  astore 2",
        "  jsr LabelPrint",
        "  return", // y is not actually live here.
        "LabelCatchEnd:",
        "LabelPrint:",
        "  astore 3",
        "  getstatic java/lang/System/out Ljava/io/PrintStream;",
        "  iload 0",
        "  invokevirtual java/io/PrintStream/println(I)V",
        "  ret 3",
        "LabelExit:",
        "  return",
        ".catch java/lang/Exception from LabelTryStart to LabelTryEnd using LabelCatchStart"
    );

    clazz.addMainMethod(
        ".limit stack 1",
        ".limit locals 1",
        "  ldc 2",
        "  invokestatic Test/foo(I)V",
        "  ldc 0",
        "  invokestatic Test/foo(I)V",
        "  return");

    String expected = "42" + ToolHelper.LINE_SEPARATOR + "0" + ToolHelper.LINE_SEPARATOR;
    String javaResult = runOnJava(builder, clazz.name);
    assertEquals(expected, javaResult);
    String artResult = runOnArtD8(builder, clazz.name);
    assertEquals(expected, artResult);
  }

  // Regression test to check that we properly add UninitializedLocal SSA values for methods that
  // have arguments without local info. To witness this bug, we also need "invalid" debug info, eg,
  // in this test the scope of "y" (local 2) spans the exceptional edge in which it is not live.
  @Test
  public void testInvalidInfoBug37722432() throws Exception {
    JasminBuilder builder = new JasminBuilder(ClassFileVersion.JSE_6);
    JasminBuilder.ClassBuilder clazz = builder.addClass("Test");

    clazz.addStaticMethod("foo", ImmutableList.of("I", "I"), "V",
        ".limit stack 2",
        ".limit locals 3",
        ".var 0 is x I from LabelInit to LabelExit",
        // Synthesized arg (no local info)
        ".var 2 is y I from LabelLocalStart to LabelExit",
        ".catch java/lang/Exception from LabelInit to LabelCatch using LabelCatch",
        "LabelInit:", // Start of try block targets catch with a state excluding 'y'.
        "  ldc 84",
        "  iload 0",
        "  idiv",
        "  istore 2",
        "LabelLocalStart:",
        "  getstatic java/lang/System/out Ljava/io/PrintStream;",
        "  iload 2",
        "  invokevirtual java/io/PrintStream/println(I)V",
        "  return",
        "LabelCatch:", // Catch target appears to include 'y' but actually does not.
        "  pop",
        "  return",
        "LabelExit:"
    );

    clazz.addMainMethod(
        ".limit stack 2",
        ".limit locals 1",
        "  ldc 2",
        "  ldc 2",
        "  invokestatic Test/foo(II)V",
        "  ldc 0",
        "  ldc 0",
        "  invokestatic Test/foo(II)V",
        "  return");

    String expected = "42" + ToolHelper.LINE_SEPARATOR;
    String javaResult = runOnJava(builder, clazz.name);
    assertEquals(expected, javaResult);
    String artResult = runOnArtD8(builder, clazz.name);
    assertEquals(expected, artResult);
  }

  @Test
  public void invalidInfoBug63412730_onWrite() throws Throwable {
    JasminBuilder builder = new JasminBuilder();
    JasminBuilder.ClassBuilder clazz = builder.addClass("Test");
    MethodSignature method = clazz.addStaticMethod("bar", ImmutableList.of(), "V",
        ".limit stack 3",
        ".limit locals 2",
        ".var 1 is i I from LI to End",
        ".var 0 is f F from LF to End",
        "Init:",
        "  ldc 42",
        "  istore 0",
        ".line 1",
        "LI:",
        "  ldc 7.5",
        "  fstore 1",
        ".line 2",
        "LF:",
        "  getstatic java/lang/System/out Ljava/io/PrintStream;",
        "  dup",
        "  iload 0",
        "  invokevirtual java/io/PrintStream/println(I)V",
        "  fload 1",
        "  invokevirtual java/io/PrintStream/println(F)V",
        ".line 3",
        "  return",
        "End:");

    clazz.addMainMethod(
        ".limit stack 1",
        ".limit locals 1",
        "  invokestatic Test/bar()V",
        "  return");

    String expected = StringUtils.lines("42", "7.5");
    String javaResult = runOnJava(builder, clazz.name);
    assertEquals(expected, javaResult);
    AndroidApp app = compileWithD8(builder);
    String artResult = runOnArt(app, clazz.name);
    assertEquals(expected, artResult);
    DebugInfoInspector info = new DebugInfoInspector(app, clazz.name, method);
    assertFalse(info.hasLocalsInfo());
  }

  @Test
  public void invalidInfoBug63412730_onRead() throws Throwable {
    JasminBuilder builder = new JasminBuilder();
    JasminBuilder.ClassBuilder clazz = builder.addClass("Test");
    MethodSignature method = clazz.addStaticMethod("bar", ImmutableList.of(), "V",
        ".limit stack 3",
        ".limit locals 2",
        ".var 1 is i I from Locals to End",
        ".var 0 is f F from Locals to End",
        "Init:",
        "  ldc 42",
        "  istore 0",
        ".line 1",
        "  ldc 7.5",
        "  fstore 1",
        ".line 2",
        "Locals:",
        "  getstatic java/lang/System/out Ljava/io/PrintStream;",
        "  dup",
        "  iload 0",
        "  invokevirtual java/io/PrintStream/println(I)V",
        "  fload 1",
        "  invokevirtual java/io/PrintStream/println(F)V",
        ".line 3",
        "  return",
        "End:");

    clazz.addMainMethod(
        ".limit stack 1",
        ".limit locals 1",
        "  invokestatic Test/bar()V",
        "  return");

    String expected = StringUtils.lines("42", "7.5");
    String javaResult = runOnJava(builder, clazz.name);
    assertEquals(expected, javaResult);
    AndroidApp app = compileWithD8(builder);
    String artResult = runOnArt(app, clazz.name);
    assertEquals(expected, artResult);
    DebugInfoInspector info = new DebugInfoInspector(app, clazz.name, method);
    assertFalse(info.hasLocalsInfo());
  }

  @Test
  public void invalidInfoBug63412730_onMove() throws Throwable {
    JasminBuilder builder = new JasminBuilder(ClassFileVersion.JSE_6);
    JasminBuilder.ClassBuilder clazz = builder.addClass("Test");
    MethodSignature method = clazz.addStaticMethod("bar", ImmutableList.of(), "V",
        ".limit stack 3",
        ".limit locals 2",
        ".var 1 is i I from LI to End",
        ".var 0 is j F from LJ to End",
        "Init:",
        "  ldc 42",
        "  istore 0",
        ".line 1",
        "LI:",
        "  ldc 0",
        "  ldc 0",
        "  ifeq LZ",
        "  ldc 75",
        "  istore 1",
        ".line 2",
        "LJ:",
        "  getstatic java/lang/System/out Ljava/io/PrintStream;",
        "  iload 1",
        "  invokevirtual java/io/PrintStream/println(I)V",
        ".line 3",
        "  return",
        "LZ:",
        "  getstatic java/lang/System/out Ljava/io/PrintStream;",
        "  iload 0",
        "  invokevirtual java/io/PrintStream/println(I)V",
        ".line 4",
        "  return",
        "End:");

    clazz.addMainMethod(
        ".limit stack 1",
        ".limit locals 1",
        "  invokestatic Test/bar()V",
        "  return");

    String expected = StringUtils.lines("42");
    String javaResult = runOnJava(builder, clazz.name);
    assertEquals(expected, javaResult);
    AndroidApp app = compileWithD8(builder);
    String artResult = runOnArt(app, clazz.name);
    assertEquals(expected, artResult);
    DebugInfoInspector info = new DebugInfoInspector(app, clazz.name, method);
    assertFalse(info.hasLocalsInfo());
  }

  @Test
  public void invalidInfoBug63412730_onPop() throws Throwable {
    JasminBuilder builder = new JasminBuilder();
    JasminBuilder.ClassBuilder clazz = builder.addClass("Test");
    MethodSignature method = clazz.addStaticMethod("bar", ImmutableList.of(), "V",
        ".limit stack 3",
        ".limit locals 2",
        ".var 1 is a [Ljava/lang/Object; from Locals to End",
        ".var 0 is o LObject; from Locals to End",
        "Init:",
        "  ldc 1",
        "  anewarray java/lang/Object",
        "  astore 0",
        ".line 1",
        "  new java/lang/Integer",
        "  dup",
        "  ldc 42",
        "  invokespecial java/lang/Integer/<init>(I)V",
        "  astore 1",
        ".line 2",
        "Locals:",
        "  aload 0",
        "  ldc 0",
        "  aload 1",
        "  aastore",
        ".line 3",
        "  getstatic java/lang/System/out Ljava/io/PrintStream;",
        "  aload 0",
        "  ldc 0",
        "  aaload",
        "  invokevirtual java/io/PrintStream/println(Ljava/lang/Object;)V",
        ".line 4",
        "  return",
        "End:");

    clazz.addMainMethod(
        ".limit stack 1",
        ".limit locals 1",
        "  invokestatic Test/bar()V",
        "  return");

    String expected = StringUtils.lines("42");
    String javaResult = runOnJava(builder, clazz.name);
    assertEquals(expected, javaResult);
    AndroidApp app = compileWithD8(builder);
    String artResult = runOnArt(app, clazz.name);
    assertEquals(expected, artResult);
    DebugInfoInspector info = new DebugInfoInspector(app, clazz.name, method);
    // Note: This code is actually invalid debug info, but we do not reject it because both types
    // are reference types. If we ever change that we should update this test.
    assertTrue(info.hasLocalsInfo());
  }
}
