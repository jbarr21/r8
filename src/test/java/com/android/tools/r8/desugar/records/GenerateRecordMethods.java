// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.desugar.records;

import static org.junit.Assert.assertEquals;

import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.TestRuntime.CfVm;
import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.cf.code.CfTypeInstruction;
import com.android.tools.r8.cfmethodgeneration.MethodGenerationBase;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.desugar.RecordRewriter;
import com.android.tools.r8.utils.FileUtils;
import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class GenerateRecordMethods extends MethodGenerationBase {
  private final DexType GENERATED_TYPE =
      factory.createType("Lcom/android/tools/r8/ir/desugar/RecordCfMethods;");
  private final List<Class<?>> METHOD_TEMPLATE_CLASSES = ImmutableList.of(RecordMethods.class);

  protected final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withCfRuntime(CfVm.JDK9).build();
  }

  public GenerateRecordMethods(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Override
  protected DexType getGeneratedType() {
    return GENERATED_TYPE;
  }

  @Override
  protected List<Class<?>> getMethodTemplateClasses() {
    return METHOD_TEMPLATE_CLASSES;
  }

  @Override
  protected int getYear() {
    return 2021;
  }

  @Override
  protected CfCode getCode(String holderName, String methodName, CfCode code) {
    DexType recordStubType =
        factory.createType("Lcom/android/tools/r8/desugar/records/RecordMethods$RecordStub;");
    code.setInstructions(
        code.getInstructions().stream()
            .map(instruction -> rewriteRecordStub(factory, instruction, recordStubType))
            .collect(Collectors.toList()));
    return code;
  }

  private CfInstruction rewriteRecordStub(
      DexItemFactory factory, CfInstruction instruction, DexType recordStubType) {
    if (instruction.isTypeInstruction()) {
      CfTypeInstruction typeInstruction = instruction.asTypeInstruction();
      return typeInstruction.withType(
          rewriteType(factory, recordStubType, typeInstruction.getType()));
    }
    if (instruction.isInvoke()) {
      CfInvoke cfInvoke = instruction.asInvoke();
      DexMethod method = cfInvoke.getMethod();
      DexMethod newMethod =
          factory.createMethod(
              rewriteType(factory, recordStubType, method.holder),
              method.proto,
              rewriteName(method.name));
      return new CfInvoke(cfInvoke.getOpcode(), newMethod, cfInvoke.isInterface());
    }
    return instruction;
  }

  private String rewriteName(DexString name) {
    return name.toString().equals("getFieldsAsObjects")
        ? RecordRewriter.GET_FIELDS_AS_OBJECTS_METHOD_NAME
        : name.toString();
  }

  private DexType rewriteType(DexItemFactory factory, DexType recordStubType, DexType type) {
    return type == recordStubType ? factory.recordType : type;
  }

  @Test
  public void testRecordMethodsGenerated() throws Exception {
    ArrayList<Class<?>> sorted = new ArrayList<>(getMethodTemplateClasses());
    sorted.sort(Comparator.comparing(Class::getTypeName));
    assertEquals("Classes should be listed in sorted order", sorted, getMethodTemplateClasses());
    assertEquals(
        FileUtils.readTextFile(getGeneratedFile(), StandardCharsets.UTF_8), generateMethods());
  }

  public static void main(String[] args) throws Exception {
    new GenerateRecordMethods(null).generateMethodsAndWriteThemToFile();
  }
}
