// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace;

import com.android.tools.r8.Keep;
import com.android.tools.r8.naming.MemberNaming;
import com.android.tools.r8.naming.MemberNaming.FieldSignature;
import com.android.tools.r8.references.FieldReference;
import com.android.tools.r8.references.FieldReference.UnknownFieldReference;
import com.android.tools.r8.references.Reference;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@Keep
public class RetraceFieldResult extends Result<RetraceFieldResult.Element, RetraceFieldResult> {

  private final RetraceClassResult.Element classElement;
  private final List<MemberNaming> memberNamings;
  private final String obfuscatedName;

  RetraceFieldResult(
      RetraceClassResult.Element classElement,
      List<MemberNaming> memberNamings,
      String obfuscatedName) {
    this.classElement = classElement;
    this.memberNamings = memberNamings;
    this.obfuscatedName = obfuscatedName;
    assert classElement != null;
    assert memberNamings == null
        || (!memberNamings.isEmpty() && memberNamings.stream().allMatch(Objects::nonNull));
  }

  private boolean hasRetraceResult() {
    return memberNamings != null;
  }

  private boolean isAmbiguous() {
    if (!hasRetraceResult()) {
      return false;
    }
    assert memberNamings != null;
    return memberNamings.size() > 1;
  }

  @Override
  public RetraceFieldResult apply(Consumer<Element> resultConsumer) {
    if (hasRetraceResult()) {
      assert !memberNamings.isEmpty();
      for (MemberNaming memberNaming : memberNamings) {
        assert memberNaming.isFieldNaming();
        FieldSignature fieldSignature = memberNaming.getOriginalSignature().asFieldSignature();
        resultConsumer.accept(
            new Element(
                this,
                classElement,
                Reference.field(
                    classElement.getClassReference(),
                    fieldSignature.name,
                    Reference.typeFromTypeName(fieldSignature.type))));
      }
    } else {
      resultConsumer.accept(
          new Element(
              this,
              classElement,
              new UnknownFieldReference(classElement.getClassReference(), obfuscatedName)));
    }
    return this;
  }

  public static class Element {

    private final FieldReference fieldReference;
    private final RetraceFieldResult retraceFieldResult;
    private final RetraceClassResult.Element classElement;

    private Element(
        RetraceFieldResult retraceFieldResult,
        RetraceClassResult.Element classElement,
        FieldReference fieldReference) {
      this.classElement = classElement;
      this.fieldReference = fieldReference;
      this.retraceFieldResult = retraceFieldResult;
    }

    public FieldReference getFieldReference() {
      return fieldReference;
    }

    public RetraceFieldResult getRetraceFieldResult() {
      return getRetraceFieldResult();
    }

    public RetraceClassResult.Element getClassElement() {
      return classElement;
    }
  }
}
