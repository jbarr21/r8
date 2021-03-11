// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.synthetic;

import com.android.tools.r8.cf.code.CfArrayStore;
import com.android.tools.r8.cf.code.CfConstNumber;
import com.android.tools.r8.cf.code.CfFieldInstruction;
import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.cf.code.CfLoad;
import com.android.tools.r8.cf.code.CfNewArray;
import com.android.tools.r8.cf.code.CfReturn;
import com.android.tools.r8.cf.code.CfStore;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.MemberType;
import com.android.tools.r8.ir.code.ValueType;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;

/**
 * Generates a method which answers all field values as an array of objects. If the field value is a
 * primitive type, it uses the primitive wrapper to wrap it.
 *
 * <p>The fields in parameters are in the order where they should be in the array generated by the
 * method, which is not necessarily the class instanceFields order.
 *
 * <p>Example: <code>record Person{ int age; String name;}</code>
 *
 * <p><code>Object[] getFieldsAsObjects() {
 * Object[] fields = new Object[2];
 * fields[0] = name;
 * fields[1] = Integer.valueOf(age);
 * return fields;</code>
 */
public class RecordGetFieldsAsObjectsCfCodeProvider extends SyntheticCfCodeProvider {

  public static void registerSynthesizedCodeReferences(DexItemFactory factory) {
    factory.createSynthesizedType("[Ljava/lang/Object;");
    factory.primitiveToBoxed.forEach(
        (primitiveType, boxedType) -> {
          factory.createSynthesizedType(primitiveType.toDescriptorString());
          factory.createSynthesizedType(boxedType.toDescriptorString());
        });
  }

  private final DexField[] fields;

  public RecordGetFieldsAsObjectsCfCodeProvider(
      AppView<?> appView, DexType holder, DexField[] fields) {
    super(appView, holder);
    this.fields = fields;
  }

  @Override
  public CfCode generateCfCode() {
    // Stack layout:
    // 0 : receiver (the record instance)
    // 1 : the array to return
    // 2+: spills
    DexItemFactory factory = appView.dexItemFactory();
    List<CfInstruction> instructions = new ArrayList<>();
    // Object[] fields = new Object[*length*];
    instructions.add(new CfConstNumber(fields.length, ValueType.INT));
    instructions.add(new CfNewArray(factory.objectArrayType));
    instructions.add(new CfStore(ValueType.OBJECT, 1));
    // fields[*i*] = this.*field* || *PrimitiveWrapper*.valueOf(this.*field*);
    for (int i = 0; i < fields.length; i++) {
      DexField field = fields[i];
      instructions.add(new CfLoad(ValueType.OBJECT, 1));
      instructions.add(new CfConstNumber(i, ValueType.INT));
      instructions.add(new CfLoad(ValueType.OBJECT, 0));
      instructions.add(new CfFieldInstruction(Opcodes.GETFIELD, field, field));
      if (field.type.isPrimitiveType()) {
        factory.primitiveToBoxed.forEach(
            (primitiveType, boxedType) -> {
              if (primitiveType == field.type) {
                instructions.add(
                    new CfInvoke(
                        Opcodes.INVOKESTATIC,
                        factory.createMethod(
                            boxedType,
                            factory.createProto(boxedType, primitiveType),
                            factory.valueOfMethodName),
                        false));
              }
            });
      }
      instructions.add(new CfArrayStore(MemberType.OBJECT));
    }
    // return fields;
    instructions.add(new CfLoad(ValueType.OBJECT, 1));
    instructions.add(new CfReturn(ValueType.OBJECT));
    return standardCfCodeFromInstructions(instructions);
  }
}
