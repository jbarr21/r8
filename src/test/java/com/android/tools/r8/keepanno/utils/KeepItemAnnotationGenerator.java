// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.keepanno.utils;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.cfmethodgeneration.CodeGenerationBase;
import com.android.tools.r8.keepanno.annotations.CheckOptimizedOut;
import com.android.tools.r8.keepanno.annotations.CheckRemoved;
import com.android.tools.r8.keepanno.annotations.ClassNamePattern;
import com.android.tools.r8.keepanno.annotations.FieldAccessFlags;
import com.android.tools.r8.keepanno.annotations.KeepBinding;
import com.android.tools.r8.keepanno.annotations.KeepCondition;
import com.android.tools.r8.keepanno.annotations.KeepConstraint;
import com.android.tools.r8.keepanno.annotations.KeepEdge;
import com.android.tools.r8.keepanno.annotations.KeepForApi;
import com.android.tools.r8.keepanno.annotations.KeepItemKind;
import com.android.tools.r8.keepanno.annotations.KeepOption;
import com.android.tools.r8.keepanno.annotations.KeepTarget;
import com.android.tools.r8.keepanno.annotations.MemberAccessFlags;
import com.android.tools.r8.keepanno.annotations.MethodAccessFlags;
import com.android.tools.r8.keepanno.annotations.StringPattern;
import com.android.tools.r8.keepanno.annotations.TypePattern;
import com.android.tools.r8.keepanno.annotations.UsedByNative;
import com.android.tools.r8.keepanno.annotations.UsedByReflection;
import com.android.tools.r8.keepanno.annotations.UsesReflection;
import com.android.tools.r8.keepanno.ast.AnnotationConstants;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.StringUtils.BraceType;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

public class KeepItemAnnotationGenerator {

  public static void main(String[] args) throws IOException {
    Generator.class.getClassLoader().setDefaultAssertionStatus(true);
    Generator.run();
  }

  private static final String DEFAULT_INVALID_STRING_PATTERN =
      "@" + simpleName(StringPattern.class) + "(exact = \"\")";
  private static final String DEFAULT_INVALID_TYPE_PATTERN =
      "@" + simpleName(TypePattern.class) + "(name = \"\")";
  private static final String DEFAULT_INVALID_CLASS_NAME_PATTERN =
      "@" + simpleName(ClassNamePattern.class) + "(simpleName = \"\")";

  public static String quote(String str) {
    return "\"" + str + "\"";
  }

  private static String simpleName(Class<?> clazz) {
    return clazz.getSimpleName();
  }

  private static class GroupMember extends DocPrinterBase<GroupMember> {

    final String name;
    String valueType = null;
    String valueDefault = null;

    GroupMember(String name) {
      this.name = name;
    }

    public GroupMember setType(String type) {
      valueType = type;
      return this;
    }

    public GroupMember setValue(String value) {
      valueDefault = value;
      return this;
    }

    @Override
    public GroupMember self() {
      return this;
    }

    void generate(Generator generator) {
      printDoc(generator::println);
      if (isDeprecated()) {
        generator.println("@Deprecated");
      }
      if (valueDefault == null) {
        generator.println(valueType + " " + name + "();");
      } else {
        generator.println(valueType + " " + name + "() default " + valueDefault + ";");
      }
    }

    public void generateConstants(Generator generator) {
      generator.println("public static final String " + name + " = " + quote(name) + ";");
    }

    public GroupMember requiredValue(Class<?> type) {
      assert valueDefault == null;
      return setType(simpleName(type));
    }

    public GroupMember requiredArrayValue(Class<?> type) {
      assert valueDefault == null;
      return setType(simpleName(type) + "[]");
    }

    public GroupMember requiredStringValue() {
      return requiredValue(String.class);
    }

    public GroupMember defaultValue(Class<?> type, String value) {
      setType(simpleName(type));
      return setValue(value);
    }

    public GroupMember defaultArrayValue(Class<?> type, String value) {
      setType(simpleName(type) + "[]");
      return setValue("{" + value + "}");
    }

    public GroupMember defaultEmptyString() {
      return defaultValue(String.class, quote(""));
    }

    public GroupMember defaultObjectClass() {
      return setType("Class<?>").setValue("Object.class");
    }

    public GroupMember defaultArrayEmpty(Class<?> type) {
      return defaultArrayValue(type, "");
    }
  }

  private static class Group {

    final String name;
    final List<GroupMember> members = new ArrayList<>();
    final List<String> footers = new ArrayList<>();
    final LinkedHashMap<String, Group> mutuallyExclusiveGroups = new LinkedHashMap<>();

    boolean mutuallyExclusiveWithOtherGroups = false;

    private Group(String name) {
      this.name = name;
    }

    Group allowMutuallyExclusiveWithOtherGroups() {
      mutuallyExclusiveWithOtherGroups = true;
      return this;
    }

    Group addMember(GroupMember member) {
      members.add(member);
      return this;
    }

    Group addDocFooterParagraph(String footer) {
      footers.add(footer);
      return this;
    }

    void generate(Generator generator) {
      assert !members.isEmpty();
      for (GroupMember member : members) {
        if (member != members.get(0)) {
          generator.println();
        }
        List<String> mutuallyExclusiveProperties = new ArrayList<>();
        for (GroupMember other : members) {
          if (!member.name.equals(other.name)) {
            mutuallyExclusiveProperties.add(other.name);
          }
        }
        mutuallyExclusiveGroups.forEach(
            (unused, group) -> {
              group.members.forEach(m -> mutuallyExclusiveProperties.add(m.name));
            });
        if (mutuallyExclusiveProperties.size() == 1) {
          member.addParagraph(
              "Mutually exclusive with the property `"
                  + mutuallyExclusiveProperties.get(0)
                  + "` also defining "
                  + name
                  + ".");
        } else if (mutuallyExclusiveProperties.size() > 1) {
          member.addParagraph(
              "Mutually exclusive with the following other properties defining " + name + ":");
          member.addUnorderedList(mutuallyExclusiveProperties);
        }
        footers.forEach(member::addParagraph);
        member.generate(generator);
      }
    }

    void generateConstants(Generator generator) {
      if (mutuallyExclusiveWithOtherGroups || members.size() > 1) {
        StringBuilder camelCaseName = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
          char c = name.charAt(i);
          if (c == '-') {
            c = Character.toUpperCase(name.charAt(++i));
          }
          camelCaseName.append(c);
        }
        generator.println(
            "public static final String " + camelCaseName + "Group = " + quote(name) + ";");
      }
      for (GroupMember member : members) {
        member.generateConstants(generator);
      }
    }

    public void addMutuallyExclusiveGroups(Group... groups) {
      for (Group group : groups) {
        assert mutuallyExclusiveWithOtherGroups || group.mutuallyExclusiveWithOtherGroups;
        mutuallyExclusiveGroups.computeIfAbsent(
            group.name,
            k -> {
              // Mutually exclusive is bidirectional so link in with other group.
              group.mutuallyExclusiveGroups.put(name, this);
              return group;
            });
      }
    }
  }

  public static class Generator {

    private static final List<Class<?>> ANNOTATION_IMPORTS =
        ImmutableList.of(ElementType.class, Retention.class, RetentionPolicy.class, Target.class);

    private final PrintStream writer;
    private int indent = 0;

    public Generator(PrintStream writer) {
      this.writer = writer;
    }

    public void withIndent(Runnable fn) {
      indent += 2;
      fn.run();
      indent -= 2;
    }

    private void println() {
      println("");
    }

    public void println(String line) {
      // Don't indent empty lines.
      if (line.length() > 0) {
        writer.print(Strings.repeat(" ", indent));
      }
      writer.println(line);
    }

    private void printCopyRight(int year) {
      println(
          CodeGenerationBase.getHeaderString(
              year, KeepItemAnnotationGenerator.class.getSimpleName()));
    }

    private void printPackage(String pkg) {
      println("package com.android.tools.r8.keepanno." + pkg + ";");
      println();
    }

    private void printImports(Class<?>... imports) {
      printImports(Arrays.asList(imports));
    }

    private void printImports(List<Class<?>> imports) {
      for (Class<?> clazz : imports) {
        println("import " + clazz.getCanonicalName() + ";");
      }
      println();
    }

    private static String KIND_GROUP = "kind";
    private static String CONSTRAINTS_GROUP = "constraints";
    private static String CLASS_GROUP = "class";
    private static String CLASS_NAME_GROUP = "class-name";
    private static String INSTANCE_OF_GROUP = "instance-of";
    private static String CLASS_ANNOTATED_BY_GROUP = "class-annotated-by";
    private static String MEMBER_ANNOTATED_BY_GROUP = "member-annotated-by";
    private static String METHOD_ANNOTATED_BY_GROUP = "method-annotated-by";
    private static String FIELD_ANNOTATED_BY_GROUP = "field-annotated-by";

    private Group createDescriptionGroup() {
      return new Group("description")
          .addMember(
              new GroupMember("description")
                  .setDocTitle("Optional description to document the reason for this annotation.")
                  .setDocReturn("The descriptive message. Defaults to no description.")
                  .defaultEmptyString());
    }

    private Group createBindingsGroup() {
      return new Group("bindings")
          .addMember(new GroupMember("bindings").defaultArrayEmpty(KeepBinding.class));
    }

    private Group createPreconditionsGroup() {
      return new Group("preconditions")
          .addMember(
              new GroupMember("preconditions")
                  .setDocTitle(
                      "Conditions that should be satisfied for the annotation to be in effect.")
                  .setDocReturn(
                      "The list of preconditions. "
                          + "Defaults to no conditions, thus trivially/unconditionally satisfied.")
                  .defaultArrayEmpty(KeepCondition.class));
    }

    private Group createConsequencesGroup() {
      return new Group("consequences")
          .addMember(
              new GroupMember("consequences")
                  .setDocTitle("Consequences that must be kept if the annotation is in effect.")
                  .setDocReturn("The list of target consequences.")
                  .requiredArrayValue(KeepTarget.class));
    }

    private Group createConsequencesAsValueGroup() {
      return new Group("consequences")
          .addMember(
              new GroupMember("value")
                  .setDocTitle("Consequences that must be kept if the annotation is in effect.")
                  .setDocReturn("The list of target consequences.")
                  .requiredArrayValue(KeepTarget.class));
    }

    private Group createAdditionalPreconditionsGroup() {
      return new Group("additional-preconditions")
          .addMember(
              new GroupMember("additionalPreconditions")
                  .setDocTitle("Additional preconditions for the annotation to be in effect.")
                  .setDocReturn(
                      "The list of additional preconditions. "
                          + "Defaults to no additional preconditions.")
                  .defaultArrayEmpty(KeepCondition.class));
    }

    private Group createAdditionalTargetsGroup(String docTitle) {
      return new Group("additional-targets")
          .addMember(
              new GroupMember("additionalTargets")
                  .setDocTitle(docTitle)
                  .setDocReturn(
                      "List of additional target consequences. "
                          + "Defaults to no additional target consequences.")
                  .defaultArrayEmpty(KeepTarget.class));
    }

    private Group stringPatternExactGroup() {
      return new Group("string-exact-pattern")
          .allowMutuallyExclusiveWithOtherGroups()
          .addMember(
              new GroupMember("exact")
                  .setDocTitle("Exact string content.")
                  .addParagraph("For example, {@code \"foo\"} or {@code \"java.lang.String\"}.")
                  .defaultEmptyString());
    }

    private Group stringPatternPrefixGroup() {
      return new Group("string-prefix-pattern")
          .addMember(
              new GroupMember("startsWith")
                  .setDocTitle("Matches strings beginning with the given prefix.")
                  .addParagraph(
                      "For example, {@code \"get\"} to match strings such as {@code"
                          + " \"getMyValue\"}.")
                  .defaultEmptyString());
    }

    private Group stringPatternSuffixGroup() {
      return new Group("string-suffix-pattern")
          .addMember(
              new GroupMember("endsWith")
                  .setDocTitle("Matches strings ending with the given suffix.")
                  .addParagraph(
                      "For example, {@code \"Setter\"} to match strings such as {@code"
                          + " \"myValueSetter\"}.")
                  .defaultEmptyString());
    }

    private Group typePatternGroup() {
      return new Group("type-pattern")
          .addMember(
              new GroupMember("name")
                  .setDocTitle("Exact type name as a string.")
                  .addParagraph("For example, {@code \"long\"} or {@code \"java.lang.String\"}.")
                  .defaultEmptyString())
          .addMember(
              new GroupMember("constant")
                  .setDocTitle("Exact type from a class constant.")
                  .addParagraph("For example, {@code String.class}.")
                  .defaultObjectClass())
          .addMember(
              new GroupMember("classNamePattern")
                  .setDocTitle("Classes matching the class-name pattern.")
                  .defaultValue(ClassNamePattern.class, DEFAULT_INVALID_CLASS_NAME_PATTERN));
      // TODO(b/248408342): Add more injections on type pattern variants.
      // /** Exact type name as a string to match any array with that type as member. */
      // String arrayOf() default "";
      //
      // /** Exact type as a class constant to match any array with that type as member. */
      // Class<?> arrayOfConstant() default TypePattern.class;
      //
      // /** If true, the pattern matches any primitive type. Such as, boolean, int, etc. */
      // boolean anyPrimitive() default false;
      //
      // /** If true, the pattern matches any array type. */
      // boolean anyArray() default false;
      //
      // /** If true, the pattern matches any class type. */
      // boolean anyClass() default false;
      //
      // /** If true, the pattern matches any reference type, namely: arrays or classes. */
      // boolean anyReference() default false;
    }

    private Group classNamePatternSimpleNameGroup() {
      return new Group("class-simple-name")
          .addMember(
              new GroupMember("simpleName")
                  .setDocTitle("Exact simple name of the class or interface.")
                  .addParagraph(
                      "For example, the simple name of {@code com.example.MyClass} is {@code"
                          + " MyClass}.")
                  .addParagraph("The default matches any simple name.")
                  .defaultEmptyString());
    }

    private Group classNamePatternPackageGroup() {
      return new Group("class-package-name")
          .addMember(
              new GroupMember("packageName")
                  .setDocTitle("Exact package name of the class or interface.")
                  .addParagraph(
                      "For example, the package of {@code com.example.MyClass} is {@code"
                          + " com.example}.")
                  .addParagraph("The default matches any package.")
                  .defaultEmptyString());
    }

    private Group getKindGroup() {
      return new Group(KIND_GROUP).addMember(getKindMember());
    }

    private static GroupMember getKindMember() {
      return new GroupMember("kind")
          .defaultValue(KeepItemKind.class, "KeepItemKind.DEFAULT")
          .setDocTitle("Specify the kind of this item pattern.")
          .setDocReturn("The kind for this pattern.")
          .addParagraph("Possible values are:")
          .addUnorderedList(
              docLink(KeepItemKind.ONLY_CLASS),
              docLink(KeepItemKind.ONLY_MEMBERS),
              docLink(KeepItemKind.ONLY_METHODS),
              docLink(KeepItemKind.ONLY_FIELDS),
              docLink(KeepItemKind.CLASS_AND_MEMBERS),
              docLink(KeepItemKind.CLASS_AND_METHODS),
              docLink(KeepItemKind.CLASS_AND_FIELDS))
          .addParagraph(
              "If unspecified the default kind for an item depends on its member patterns:")
          .addUnorderedList(
              docLink(KeepItemKind.ONLY_CLASS) + " if no member patterns are defined",
              docLink(KeepItemKind.ONLY_METHODS) + " if method patterns are defined",
              docLink(KeepItemKind.ONLY_FIELDS) + " if field patterns are defined",
              docLink(KeepItemKind.ONLY_MEMBERS) + " otherwise.");
    }

    private Group getKeepConstraintsGroup() {
      return new Group(CONSTRAINTS_GROUP)
          .addMember(constraints())
          .addMember(
              new GroupMember("allow")
                  .setDeprecated("Use " + docLink(constraints()) + " instead.")
                  .setDocTitle(
                      "Define the " + CONSTRAINTS_GROUP + " that are allowed to be modified.")
                  .addParagraph(
                      "The specified option constraints do not need to be preserved for the"
                          + " target.")
                  .setDocReturn("Option constraints allowed to be modified for the target.")
                  .defaultArrayEmpty(KeepOption.class))
          .addMember(
              new GroupMember("disallow")
                  .setDeprecated("Use " + docLink(constraints()) + " instead.")
                  .setDocTitle(
                      "Define the " + CONSTRAINTS_GROUP + " that are not allowed to be modified.")
                  .addParagraph(
                      "The specified option constraints *must* be preserved for the target.")
                  .setDocReturn("Option constraints not allowed to be modified for the target.")
                  .defaultArrayEmpty(KeepOption.class))
          .addDocFooterParagraph(
              "If nothing is specified for "
                  + CONSTRAINTS_GROUP
                  + " the default is the default for "
                  + docLink(constraints())
                  + ".");
    }

    private static String docLinkList(Enum<?>... values) {
      return StringUtils.join(", ", values, v -> docLink(v), BraceType.TUBORG);
    }

    private static GroupMember constraints() {
      return new GroupMember("constraints")
          .setDocTitle("Define the usage constraints of the target.")
          .addParagraph("The specified constraints must remain valid for the target.")
          .addParagraph("The default constraints depend on the type of the target.")
          .addUnorderedList(
              "For classes, the default is "
                  + docLinkList(
                      KeepConstraint.LOOKUP, KeepConstraint.NAME, KeepConstraint.CLASS_INSTANTIATE),
              "For methods, the default is "
                  + docLinkList(
                      KeepConstraint.LOOKUP, KeepConstraint.NAME, KeepConstraint.METHOD_INVOKE),
              "For fields, the default is "
                  + docLinkList(
                      KeepConstraint.LOOKUP,
                      KeepConstraint.NAME,
                      KeepConstraint.FIELD_GET,
                      KeepConstraint.FIELD_SET))
          .setDocReturn("Usage constraints for the target.")
          .defaultArrayEmpty(KeepConstraint.class);
    }

    private GroupMember bindingName() {
      return new GroupMember("bindingName")
          .setDocTitle(
              "Name with which other bindings, conditions or targets "
                  + "can reference the bound item pattern.")
          .setDocReturn("Name of the binding.")
          .requiredStringValue();
    }

    private GroupMember classFromBinding() {
      return new GroupMember("classFromBinding")
          .setDocTitle("Define the " + CLASS_GROUP + " pattern by reference to a binding.")
          .setDocReturn("The name of the binding that defines the class.")
          .defaultEmptyString();
    }

    private Group createClassBindingGroup() {
      return new Group(CLASS_GROUP)
          .allowMutuallyExclusiveWithOtherGroups()
          .addMember(classFromBinding())
          .addDocFooterParagraph("If none are specified the default is to match any class.");
    }

    private GroupMember className() {
      return new GroupMember("className")
          .setDocTitle("Define the " + CLASS_NAME_GROUP + " pattern by fully qualified class name.")
          .setDocReturn("The qualified class name that defines the class.")
          .defaultEmptyString();
    }

    private GroupMember classConstant() {
      return new GroupMember("classConstant")
          .setDocTitle(
              "Define the " + CLASS_NAME_GROUP + " pattern by reference to a Class constant.")
          .setDocReturn("The class-constant that defines the class.")
          .defaultObjectClass();
    }

    private GroupMember classNamePattern() {
      return new GroupMember("classNamePattern")
          .setDocTitle(
              "Define the " + CLASS_NAME_GROUP + " pattern by reference to a class-name pattern.")
          .setDocReturn("The class-name pattern that defines the class.")
          .defaultValue(ClassNamePattern.class, DEFAULT_INVALID_CLASS_NAME_PATTERN);
    }

    private Group createClassNamePatternGroup() {
      return new Group(CLASS_NAME_GROUP)
          .addMember(className())
          .addMember(classConstant())
          .addMember(classNamePattern())
          .addDocFooterParagraph("If none are specified the default is to match any class name.");
    }

    private GroupMember instanceOfClassName() {
      return new GroupMember("instanceOfClassName")
          .setDocTitle(
              "Define the "
                  + INSTANCE_OF_GROUP
                  + " pattern as classes that are instances of the fully qualified class name.")
          .setDocReturn("The qualified class name that defines what instance-of the class must be.")
          .defaultEmptyString();
    }

    private GroupMember instanceOfClassConstant() {
      return new GroupMember("instanceOfClassConstant")
          .setDocTitle(
              "Define the "
                  + INSTANCE_OF_GROUP
                  + " pattern as classes that are instances the referenced Class constant.")
          .setDocReturn("The class constant that defines what instance-of the class must be.")
          .defaultObjectClass();
    }

    private String getInstanceOfExclusiveDoc() {
      return "The pattern is exclusive in that it does not match classes that are"
          + " instances of the pattern, but only those that are instances of classes that"
          + " are subclasses of the pattern.";
    }

    private GroupMember instanceOfClassNameExclusive() {
      return new GroupMember("instanceOfClassNameExclusive")
          .setDocTitle(
              "Define the "
                  + INSTANCE_OF_GROUP
                  + " pattern as classes that are instances of the fully qualified class name.")
          .setDocReturn("The qualified class name that defines what instance-of the class must be.")
          .addParagraph(getInstanceOfExclusiveDoc())
          .defaultEmptyString();
    }

    private GroupMember instanceOfClassConstantExclusive() {
      return new GroupMember("instanceOfClassConstantExclusive")
          .setDocTitle(
              "Define the "
                  + INSTANCE_OF_GROUP
                  + " pattern as classes that are instances the referenced Class constant.")
          .addParagraph(getInstanceOfExclusiveDoc())
          .setDocReturn("The class constant that defines what instance-of the class must be.")
          .defaultObjectClass();
    }

    private GroupMember extendsClassName() {
      return new GroupMember("extendsClassName")
          .setDocTitle(
              "Define the "
                  + INSTANCE_OF_GROUP
                  + " pattern as classes extending the fully qualified class name.")
          .addParagraph(getInstanceOfExclusiveDoc())
          .setDeprecated(
              "This property is deprecated, use " + docLink(instanceOfClassName()) + " instead.")
          .setDocReturn("The class name that defines what the class must extend.")
          .defaultEmptyString();
    }

    private GroupMember extendsClassConstant() {
      return new GroupMember("extendsClassConstant")
          .setDocTitle(
              "Define the "
                  + INSTANCE_OF_GROUP
                  + " pattern as classes extending the referenced Class constant.")
          .addParagraph(getInstanceOfExclusiveDoc())
          .setDeprecated(
              "This property is deprecated, use "
                  + docLink(instanceOfClassConstant())
                  + " instead.")
          .setDocReturn("The class constant that defines what the class must extend.")
          .defaultObjectClass();
    }

    private Group createClassInstanceOfPatternGroup() {
      return new Group(INSTANCE_OF_GROUP)
          .addMember(instanceOfClassName())
          .addMember(instanceOfClassNameExclusive())
          .addMember(instanceOfClassConstant())
          .addMember(instanceOfClassConstantExclusive())
          .addMember(extendsClassName())
          .addMember(extendsClassConstant())
          .addDocFooterParagraph(
              "If none are specified the default is to match any class instance.");
    }

    private String annotatedByDefaultDocFooter(String name) {
      return "If none are specified the default is to match any "
          + name
          + " regardless of what the "
          + name
          + " is annotated by.";
    }

    private Group createAnnotatedByPatternGroup(String name, String groupName) {
      return new Group(groupName)
          .addMember(
              new GroupMember(name + "AnnotatedByClassName")
                  .setDocTitle(
                      "Define the " + groupName + " pattern by fully qualified class name.")
                  .setDocReturn("The qualified class name that defines the annotation.")
                  .defaultEmptyString())
          .addMember(
              new GroupMember(name + "AnnotatedByClassConstant")
                  .setDocTitle(
                      "Define the " + groupName + " pattern by reference to a Class constant.")
                  .setDocReturn("The class-constant that defines the annotation.")
                  .defaultObjectClass())
          .addMember(
              new GroupMember(name + "AnnotatedByClassNamePattern")
                  .setDocTitle(
                      "Define the " + groupName + " pattern by reference to a class-name pattern.")
                  .setDocReturn("The class-name pattern that defines the annotation.")
                  .defaultValue(ClassNamePattern.class, DEFAULT_INVALID_CLASS_NAME_PATTERN));
    }

    private Group createClassAnnotatedByPatternGroup() {
      String name = "class";
      return createAnnotatedByPatternGroup(name, CLASS_ANNOTATED_BY_GROUP)
          .addDocFooterParagraph(annotatedByDefaultDocFooter(name));
    }

    private Group createMemberBindingGroup() {
      return new Group("member")
          .allowMutuallyExclusiveWithOtherGroups()
          .addMember(
              new GroupMember("memberFromBinding")
                  .setDocTitle("Define the member pattern in full by a reference to a binding.")
                  .addParagraph(
                      "Mutually exclusive with all other class and member pattern properties.",
                      "When a member binding is referenced this item is defined to be that item,",
                      "including its class and member patterns.")
                  .setDocReturn("The binding name that defines the member.")
                  .defaultEmptyString());
    }

    private Group createMemberAnnotatedByGroup() {
      String name = "member";
      return createAnnotatedByPatternGroup(name, MEMBER_ANNOTATED_BY_GROUP)
          .addDocFooterParagraph(getMutuallyExclusiveForMemberProperties())
          .addDocFooterParagraph(annotatedByDefaultDocFooter(name));
    }

    private Group createMemberAccessGroup() {
      return new Group("member-access")
          .addMember(
              new GroupMember("memberAccess")
                  .setDocTitle("Define the member-access pattern by matching on access flags.")
                  .addParagraph(getMutuallyExclusiveForMemberProperties())
                  .setDocReturn("The member access-flag constraints that must be met.")
                  .defaultArrayEmpty(MemberAccessFlags.class));
    }

    private String getMutuallyExclusiveForMemberProperties() {
      return "Mutually exclusive with all field and method properties "
          + "as use restricts the match to both types of members.";
    }

    private String getMutuallyExclusiveForMethodProperties() {
      return "Mutually exclusive with all field properties.";
    }

    private String getMutuallyExclusiveForFieldProperties() {
      return "Mutually exclusive with all method properties.";
    }

    private String getMethodDefaultDoc(String suffix) {
      return "If none, and other properties define this item as a method, the default matches "
          + suffix
          + ".";
    }

    private String getFieldDefaultDoc(String suffix) {
      return "If none, and other properties define this item as a field, the default matches "
          + suffix
          + ".";
    }

    private Group createMethodAnnotatedByGroup() {
      String name = "method";
      return createAnnotatedByPatternGroup(name, METHOD_ANNOTATED_BY_GROUP)
          .addDocFooterParagraph(getMutuallyExclusiveForMethodProperties())
          .addDocFooterParagraph(annotatedByDefaultDocFooter(name));
    }

    private Group createMethodAccessGroup() {
      return new Group("method-access")
          .addMember(
              new GroupMember("methodAccess")
                  .setDocTitle("Define the method-access pattern by matching on access flags.")
                  .addParagraph(getMutuallyExclusiveForMethodProperties())
                  .addParagraph(getMethodDefaultDoc("any method-access flags"))
                  .setDocReturn("The method access-flag constraints that must be met.")
                  .defaultArrayEmpty(MethodAccessFlags.class));
    }

    private Group createMethodNameGroup() {
      return new Group("method-name")
          .addMember(
              new GroupMember("methodName")
                  .setDocTitle("Define the method-name pattern by an exact method name.")
                  .addParagraph(getMutuallyExclusiveForMethodProperties())
                  .addParagraph(getMethodDefaultDoc("any method name"))
                  .setDocReturn("The exact method name of the method.")
                  .defaultEmptyString())
          .addMember(
              new GroupMember("methodNamePattern")
                  .setDocTitle("Define the method-name pattern by a string pattern.")
                  .addParagraph(getMutuallyExclusiveForMethodProperties())
                  .addParagraph(getMethodDefaultDoc("any method name"))
                  .setDocReturn("The string pattern of the method name.")
                  .defaultValue(StringPattern.class, DEFAULT_INVALID_STRING_PATTERN));
    }

    private Group createMethodReturnTypeGroup() {
      return new Group("return-type")
          .addMember(
              new GroupMember("methodReturnType")
                  .setDocTitle(
                      "Define the method return-type pattern by a fully qualified type or 'void'.")
                  .addParagraph(getMutuallyExclusiveForMethodProperties())
                  .addParagraph(getMethodDefaultDoc("any return type"))
                  .setDocReturn("The qualified type name of the method return type.")
                  .defaultEmptyString())
          .addMember(
              new GroupMember("methodReturnTypeConstant")
                  .setDocTitle("Define the method return-type pattern by a class constant.")
                  .addParagraph(getMutuallyExclusiveForMethodProperties())
                  .addParagraph(getMethodDefaultDoc("any return type"))
                  .setDocReturn("A class constant denoting the type of the method return type.")
                  .defaultObjectClass())
          .addMember(
              new GroupMember("methodReturnTypePattern")
                  .setDocTitle("Define the method return-type pattern by a type pattern.")
                  .addParagraph(getMutuallyExclusiveForMethodProperties())
                  .addParagraph(getMethodDefaultDoc("any return type"))
                  .setDocReturn("The pattern of the method return type.")
                  .defaultValue(TypePattern.class, DEFAULT_INVALID_TYPE_PATTERN));
    }

    private Group createMethodParametersGroup() {
      return new Group("parameters")
          .addMember(
              new GroupMember("methodParameters")
                  .setDocTitle(
                      "Define the method parameters pattern by a list of fully qualified types.")
                  .addParagraph(getMutuallyExclusiveForMethodProperties())
                  .addParagraph(getMethodDefaultDoc("any parameters"))
                  .setDocReturn("The list of qualified type names of the method parameters.")
                  .defaultArrayValue(String.class, quote("")))
          .addMember(
              new GroupMember("methodParameterTypePatterns")
                  .setDocTitle(
                      "Define the method parameters pattern by a list of patterns on types.")
                  .addParagraph(getMutuallyExclusiveForMethodProperties())
                  .addParagraph(getMethodDefaultDoc("any parameters"))
                  .setDocReturn("The list of type patterns for the method parameters.")
                  .defaultArrayValue(TypePattern.class, DEFAULT_INVALID_TYPE_PATTERN));
    }

    private Group createFieldAnnotatedByGroup() {
      String name = "field";
      return createAnnotatedByPatternGroup(name, FIELD_ANNOTATED_BY_GROUP)
          .addDocFooterParagraph(getMutuallyExclusiveForFieldProperties())
          .addDocFooterParagraph(annotatedByDefaultDocFooter(name));
    }

    private Group createFieldAccessGroup() {
      return new Group("field-access")
          .addMember(
              new GroupMember("fieldAccess")
                  .setDocTitle("Define the field-access pattern by matching on access flags.")
                  .addParagraph(getMutuallyExclusiveForFieldProperties())
                  .addParagraph(getFieldDefaultDoc("any field-access flags"))
                  .setDocReturn("The field access-flag constraints that must be met.")
                  .defaultArrayEmpty(FieldAccessFlags.class));
    }

    private Group createFieldNameGroup() {
      return new Group("field-name")
          .addMember(
              new GroupMember("fieldName")
                  .setDocTitle("Define the field-name pattern by an exact field name.")
                  .addParagraph(getMutuallyExclusiveForFieldProperties())
                  .addParagraph(getFieldDefaultDoc("any field name"))
                  .setDocReturn("The exact field name of the field.")
                  .defaultEmptyString())
          .addMember(
              new GroupMember("fieldNamePattern")
                  .setDocTitle("Define the field-name pattern by a string pattern.")
                  .addParagraph(getMutuallyExclusiveForFieldProperties())
                  .addParagraph(getFieldDefaultDoc("any field name"))
                  .setDocReturn("The string pattern of the field name.")
                  .defaultValue(StringPattern.class, DEFAULT_INVALID_STRING_PATTERN));
    }

    private Group createFieldTypeGroup() {
      return new Group("field-type")
          .addMember(
              new GroupMember("fieldType")
                  .setDocTitle("Define the field-type pattern by a fully qualified type.")
                  .addParagraph(getMutuallyExclusiveForFieldProperties())
                  .addParagraph(getFieldDefaultDoc("any type"))
                  .setDocReturn("The qualified type name for the field type.")
                  .defaultEmptyString())
          .addMember(
              new GroupMember("fieldTypeConstant")
                  .setDocTitle("Define the field-type pattern by a class constant.")
                  .addParagraph(getMutuallyExclusiveForFieldProperties())
                  .addParagraph(getFieldDefaultDoc("any type"))
                  .setDocReturn("The class constant for the field type.")
                  .defaultObjectClass())
          .addMember(
              new GroupMember("fieldTypePattern")
                  .setDocTitle("Define the field-type pattern by a pattern on types.")
                  .addParagraph(getMutuallyExclusiveForFieldProperties())
                  .addParagraph(getFieldDefaultDoc("any type"))
                  .setDocReturn("The type pattern for the field type.")
                  .defaultValue(TypePattern.class, DEFAULT_INVALID_TYPE_PATTERN));
    }

    private void generateClassAndMemberPropertiesWithClassAndMemberBinding() {
      internalGenerateClassAndMemberPropertiesWithBinding(true);
    }

    private void generateClassAndMemberPropertiesWithClassBinding() {
      internalGenerateClassAndMemberPropertiesWithBinding(false);
    }

    private void internalGenerateClassAndMemberPropertiesWithBinding(boolean includeMemberBinding) {
      // Class properties.
      {
        Group bindingGroup = createClassBindingGroup();
        Group classNameGroup = createClassNamePatternGroup();
        Group classInstanceOfGroup = createClassInstanceOfPatternGroup();
        Group classAnnotatedByGroup = createClassAnnotatedByPatternGroup();
        bindingGroup.addMutuallyExclusiveGroups(
            classNameGroup, classInstanceOfGroup, classAnnotatedByGroup);

        bindingGroup.generate(this);
        println();
        classNameGroup.generate(this);
        println();
        classInstanceOfGroup.generate(this);
        println();
        classAnnotatedByGroup.generate(this);
        println();
      }

      // Member binding properties.
      Group memberBindingGroup = null;
      if (includeMemberBinding) {
        memberBindingGroup = createMemberBindingGroup();
        memberBindingGroup.generate(this);
        println();
      }

      // The remaining member properties.
      internalGenerateMemberPropertiesNoBinding(memberBindingGroup);
    }

    private Group maybeLink(Group group, Group maybeExclusiveGroup) {
      if (maybeExclusiveGroup != null) {
        maybeExclusiveGroup.addMutuallyExclusiveGroups(group);
      }
      return group;
    }

    private void generateMemberPropertiesNoBinding() {
      internalGenerateMemberPropertiesNoBinding(null);
    }

    private void internalGenerateMemberPropertiesNoBinding(Group memberBindingGroup) {
      // General member properties.
      maybeLink(createMemberAnnotatedByGroup(), memberBindingGroup).generate(this);
      println();
      maybeLink(createMemberAccessGroup(), memberBindingGroup).generate(this);
      println();

      // Method properties.
      maybeLink(createMethodAnnotatedByGroup(), memberBindingGroup).generate(this);
      println();
      maybeLink(createMethodAccessGroup(), memberBindingGroup).generate(this);
      println();
      maybeLink(createMethodNameGroup(), memberBindingGroup).generate(this);
      println();
      maybeLink(createMethodReturnTypeGroup(), memberBindingGroup).generate(this);
      println();
      maybeLink(createMethodParametersGroup(), memberBindingGroup).generate(this);
      println();

      // Field properties.
      maybeLink(createFieldAnnotatedByGroup(), memberBindingGroup).generate(this);
      println();
      maybeLink(createFieldAccessGroup(), memberBindingGroup).generate(this);
      println();
      maybeLink(createFieldNameGroup(), memberBindingGroup).generate(this);
      println();
      maybeLink(createFieldTypeGroup(), memberBindingGroup).generate(this);
    }

    private void generateStringPattern() {
      printCopyRight(2024);
      printPackage("annotations");
      printImports(ANNOTATION_IMPORTS);
      DocPrinter.printer()
          .setDocTitle("A pattern structure for matching strings.")
          .addParagraph("If no properties are set, the default pattern matches any string.")
          .printDoc(this::println);
      println("@Target(ElementType.ANNOTATION_TYPE)");
      println("@Retention(RetentionPolicy.CLASS)");
      println("public @interface " + simpleName(StringPattern.class) + " {");
      println();
      withIndent(
          () -> {
            Group exactGroup = stringPatternExactGroup();
            Group prefixGroup = stringPatternPrefixGroup();
            Group suffixGroup = stringPatternSuffixGroup();
            exactGroup.addMutuallyExclusiveGroups(prefixGroup, suffixGroup);
            exactGroup.generate(this);
            println();
            prefixGroup.generate(this);
            println();
            suffixGroup.generate(this);
          });
      println();
      println("}");
    }

    private void generateTypePattern() {
      printCopyRight(2023);
      printPackage("annotations");
      printImports(ANNOTATION_IMPORTS);
      DocPrinter.printer()
          .setDocTitle("A pattern structure for matching types.")
          .addParagraph("If no properties are set, the default pattern matches any type.")
          .addParagraph("All properties on this annotation are mutually exclusive.")
          .printDoc(this::println);
      println("@Target(ElementType.ANNOTATION_TYPE)");
      println("@Retention(RetentionPolicy.CLASS)");
      println("public @interface " + simpleName(TypePattern.class) + " {");
      println();
      withIndent(() -> typePatternGroup().generate(this));
      println();
      println("}");
    }

    private void generateClassNamePattern() {
      printCopyRight(2023);
      printPackage("annotations");
      printImports(ANNOTATION_IMPORTS);
      DocPrinter.printer()
          .setDocTitle("A pattern structure for matching names of classes and interfaces.")
          .addParagraph(
              "If no properties are set, the default pattern matches any name of a class or"
                  + " interface.")
          .printDoc(this::println);
      println("@Target(ElementType.ANNOTATION_TYPE)");
      println("@Retention(RetentionPolicy.CLASS)");
      println("public @interface " + simpleName(ClassNamePattern.class) + " {");
      println();
      withIndent(
          () -> {
            classNamePatternSimpleNameGroup().generate(this);
            println();
            classNamePatternPackageGroup().generate(this);
          });
      println();
      println("}");
    }

    private void generateKeepBinding() {
      printCopyRight(2022);
      printPackage("annotations");
      printImports(ANNOTATION_IMPORTS);
      DocPrinter.printer()
          .setDocTitle("A binding of a keep item.")
          .addParagraph(
              "Bindings allow referencing the exact instance of a match from a condition in other "
                  + " conditions and/or targets. It can also be used to reduce duplication of"
                  + " targets by sharing patterns.")
          .addParagraph("An item can be:")
          .addUnorderedList(
              "a pattern on classes;", "a pattern on methods; or", "a pattern on fields.")
          .printDoc(this::println);
      println("@Target(ElementType.ANNOTATION_TYPE)");
      println("@Retention(RetentionPolicy.CLASS)");
      println("public @interface KeepBinding {");
      println();
      withIndent(
          () -> {
            bindingName().generate(this);
            println();
            getKindGroup().generate(this);
            println();
            generateClassAndMemberPropertiesWithClassBinding();
          });
      println();
      println("}");
    }

    private void generateKeepTarget() {
      printCopyRight(2022);
      printPackage("annotations");
      printImports(ANNOTATION_IMPORTS);
      DocPrinter.printer()
          .setDocTitle("A target for a keep edge.")
          .addParagraph(
              "The target denotes an item along with options for what to keep. An item can be:")
          .addUnorderedList(
              "a pattern on classes;", "a pattern on methods; or", "a pattern on fields.")
          .printDoc(this::println);
      println("@Target(ElementType.ANNOTATION_TYPE)");
      println("@Retention(RetentionPolicy.CLASS)");
      println("public @interface KeepTarget {");
      println();
      withIndent(
          () -> {
            getKindGroup().generate(this);
            println();
            getKeepConstraintsGroup().generate(this);
            println();
            generateClassAndMemberPropertiesWithClassAndMemberBinding();
          });
      println();
      println("}");
    }

    private void generateKeepCondition() {
      printCopyRight(2022);
      printPackage("annotations");
      printImports(ANNOTATION_IMPORTS);
      DocPrinter.printer()
          .setDocTitle("A condition for a keep edge.")
          .addParagraph(
              "The condition denotes an item used as a precondition of a rule. An item can be:")
          .addUnorderedList(
              "a pattern on classes;", "a pattern on methods; or", "a pattern on fields.")
          .printDoc(this::println);
      println("@Target(ElementType.ANNOTATION_TYPE)");
      println("@Retention(RetentionPolicy.CLASS)");
      println("public @interface KeepCondition {");
      println();
      withIndent(
          () -> {
            generateClassAndMemberPropertiesWithClassAndMemberBinding();
          });
      println();
      println("}");
    }

    private void generateKeepForApi() {
      printCopyRight(2023);
      printPackage("annotations");
      printImports(ANNOTATION_IMPORTS);
      DocPrinter.printer()
          .setDocTitle(
              "Annotation to mark a class, field or method as part of a library API surface.")
          .addParagraph(
              "When a class is annotated, member patterns can be used to define which members are"
                  + " to be kept. When no member patterns are specified the default pattern matches"
                  + " all public and protected members.")
          .addParagraph(
              "When a member is annotated, the member patterns cannot be used as the annotated"
                  + " member itself fully defines the item to be kept (i.e., itself).")
          .printDoc(this::println);
      println(
          "@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD,"
              + " ElementType.CONSTRUCTOR})");
      println("@Retention(RetentionPolicy.CLASS)");
      println("public @interface KeepForApi {");
      println();
      withIndent(
          () -> {
            createDescriptionGroup().generate(this);
            println();
            createAdditionalTargetsGroup(
                    "Additional targets to be kept as part of the API surface.")
                .generate(this);
            println();
            GroupMember kindProperty = getKindMember();
            kindProperty
                .clearDocLines()
                .addParagraph(
                    "Default kind is",
                    KeepItemKind.CLASS_AND_MEMBERS.name(),
                    ", meaning the annotated class and/or member is to be kept.",
                    "When annotating a class this can be set to",
                    KeepItemKind.ONLY_CLASS.name(),
                    "to avoid patterns on any members.",
                    "That can be useful when the API members are themselves explicitly annotated.")
                .addParagraph(
                    "It is not possible to use",
                    KeepItemKind.ONLY_CLASS.name(),
                    "if annotating a member. Also, it is never valid to use kind",
                    KeepItemKind.ONLY_MEMBERS.name(),
                    "as the API surface must keep the class if any member is to be accessible.")
                .generate(this);
            println();
            generateMemberPropertiesNoBinding();
          });
      println();
      println("}");
    }

    private void generateUsesReflection() {
      printCopyRight(2022);
      printPackage("annotations");
      printImports(ANNOTATION_IMPORTS);
      DocPrinter.printer()
          .setDocTitle(
              "Annotation to declare the reflective usages made by a class, method or field.")
          .addParagraph(
              "The annotation's 'value' is a list of targets to be kept if the annotated item is"
                  + " used. The annotated item is a precondition for keeping any of the specified"
                  + " targets. Thus, if an annotated method is determined to be unused by the"
                  + " program, the annotation itself will not be in effect and the targets will not"
                  + " be kept (assuming nothing else is otherwise keeping them).")
          .addParagraph(
              "The annotation's 'additionalPreconditions' is optional and can specify additional"
                  + " conditions that should be satisfied for the annotation to be in effect.")
          .addParagraph(
              "The translation of the "
                  + docLink(UsesReflection.class)
                  + " annotation into a "
                  + docLink(KeepEdge.class)
                  + " is as follows:")
          .addParagraph(
              "Assume the item of the annotation is denoted by 'CTX' and referred to as its"
                  + " context.")
          .addCodeBlock(
              annoSimpleName(UsesReflection.class)
                  + "(value = targets, [additionalPreconditions = preconditions])",
              "==>",
              annoSimpleName(KeepEdge.class) + "(",
              "  consequences = targets,",
              "  preconditions = {createConditionFromContext(CTX)} + preconditions",
              ")",
              "",
              "where",
              "  KeepCondition createConditionFromContext(ctx) {",
              "    if (ctx.isClass()) {",
              "      return new KeepCondition(classTypeName = ctx.getClassTypeName());",
              "    }",
              "    if (ctx.isMethod()) {",
              "      return new KeepCondition(",
              "        classTypeName = ctx.getClassTypeName(),",
              "        methodName = ctx.getMethodName(),",
              "        methodReturnType = ctx.getMethodReturnType(),",
              "        methodParameterTypes = ctx.getMethodParameterTypes());",
              "    }",
              "    if (ctx.isField()) {",
              "      return new KeepCondition(",
              "        classTypeName = ctx.getClassTypeName(),",
              "        fieldName = ctx.getFieldName()",
              "        fieldType = ctx.getFieldType());",
              "    }",
              "    // unreachable",
              "  }")
          .printDoc(this::println);
      println(
          "@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD,"
              + " ElementType.CONSTRUCTOR})");
      println("@Retention(RetentionPolicy.CLASS)");
      println("public @interface " + simpleName(UsesReflection.class) + " {");
      println();
      withIndent(
          () -> {
            createDescriptionGroup().generate(this);
            println();
            createConsequencesAsValueGroup().generate(this);
            println();
            createAdditionalPreconditionsGroup().generate(this);
          });
      println("}");
    }

    private void generateUsedByX(String annotationClassName, String doc) {
      printCopyRight(2023);
      printPackage("annotations");
      printImports(ANNOTATION_IMPORTS);
      DocPrinter.printer()
          .setDocTitle("Annotation to mark a class, field or method as being " + doc + ".")
          .addParagraph(
              "Note: Before using this annotation, consider if instead you can annotate the code"
                  + " that is doing reflection with "
                  + docLink(UsesReflection.class)
                  + ". Annotating the"
                  + " reflecting code is generally more clear and maintainable, and it also"
                  + " naturally gives rise to edges that describe just the reflected aspects of the"
                  + " program. The "
                  + docLink(UsedByReflection.class)
                  + " annotation is suitable for cases where"
                  + " the reflecting code is not under user control, or in migrating away from"
                  + " rules.")
          .addParagraph(
              "When a class is annotated, member patterns can be used to define which members are"
                  + " to be kept. When no member patterns are specified the default pattern is to"
                  + " match just the class.")
          .addParagraph(
              "When a member is annotated, the member patterns cannot be used as the annotated"
                  + " member itself fully defines the item to be kept (i.e., itself).")
          .printDoc(this::println);
      println(
          "@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD,"
              + " ElementType.CONSTRUCTOR})");
      println("@Retention(RetentionPolicy.CLASS)");
      println("public @interface " + annotationClassName + " {");
      println();
      withIndent(
          () -> {
            createDescriptionGroup().generate(this);
            println();
            createPreconditionsGroup().generate(this);
            println();
            createAdditionalTargetsGroup(
                    "Additional targets to be kept in addition to the annotated class/members.")
                .generate(this);
            println();
            GroupMember kindProperty = getKindMember();
            kindProperty
                .clearDocLines()
                .addParagraph("If unspecified the default kind depends on the annotated item.")
                .addParagraph("When annotating a class the default kind is:")
                .addUnorderedList(
                    docLink(KeepItemKind.ONLY_CLASS) + " if no member patterns are defined;",
                    docLink(KeepItemKind.CLASS_AND_METHODS) + " if method patterns are defined;",
                    docLink(KeepItemKind.CLASS_AND_FIELDS) + " if field patterns are defined;",
                    docLink(KeepItemKind.CLASS_AND_MEMBERS) + "otherwise.")
                .addParagraph(
                    "When annotating a method the default kind is: "
                        + docLink(KeepItemKind.ONLY_METHODS))
                .addParagraph(
                    "When annotating a field the default kind is: "
                        + docLink(KeepItemKind.ONLY_FIELDS))
                .addParagraph(
                    "It is not possible to use "
                        + docLink(KeepItemKind.ONLY_CLASS)
                        + " if annotating a member.")
                .generate(this);
            println();
            constraints().generate(this);
            println();
            generateMemberPropertiesNoBinding();
          });
      println();
      println("}");
    }

    private static String annoSimpleName(Class<?> clazz) {
      return "@" + simpleName(clazz);
    }

    private static String docLink(Class<?> clazz) {
      return "{@link " + simpleName(clazz) + "}";
    }

    private static String docLink(GroupMember member) {
      return "{@link #" + member.name + "}";
    }

    private static String docLink(Enum<?> kind) {
      return "{@link " + simpleName(kind.getClass()) + "#" + kind.name() + "}";
    }

    private void generateConstants() {
      printCopyRight(2023);
      printPackage("ast");
      printImports();
      DocPrinter.printer()
          .setDocTitle(
              "Utility class for referencing the various keep annotations and their structure.")
          .addParagraph(
              "Use of these references avoids polluting the Java namespace with imports of the java"
                  + " annotations which overlap in name with the actual semantic AST types.")
          .printDoc(this::println);
      println("public final class AnnotationConstants {");
      withIndent(
          () -> {
            // Root annotations.
            generateKeepEdgeConstants();
            generateKeepForApiConstants();
            generateUsesReflectionConstants();
            generateUsedByReflectionConstants();
            generateUsedByNativeConstants();
            generateCheckRemovedConstants();
            generateCheckOptimizedOutConstants();
            // Common item fields.
            generateItemConstants();
            // Inner annotation classes.
            generateBindingConstants();
            generateConditionConstants();
            generateTargetConstants();
            generateKindConstants();
            generateConstraintConstants();
            generateOptionConstants();
            generateMemberAccessConstants();
            generateMethodAccessConstants();
            generateFieldAccessConstants();

            generateStringPatternConstants();
            generateTypePatternConstants();
            generateClassNamePatternConstants();
          });
      println("}");
    }

    private void generateAnnotationConstants(Class<?> clazz) {
      String desc = TestBase.descriptor(clazz);
      println("public static final String DESCRIPTOR = " + quote(desc) + ";");
    }

    private void generateKeepEdgeConstants() {
      println("public static final class Edge {");
      withIndent(
          () -> {
            generateAnnotationConstants(KeepEdge.class);
            createDescriptionGroup().generateConstants(this);
            createBindingsGroup().generateConstants(this);
            createPreconditionsGroup().generateConstants(this);
            createConsequencesGroup().generateConstants(this);
          });
      println("}");
      println();
    }

    private void generateKeepForApiConstants() {
      println("public static final class ForApi {");
      withIndent(
          () -> {
            generateAnnotationConstants(KeepForApi.class);
            createDescriptionGroup().generateConstants(this);
            createAdditionalTargetsGroup(".").generateConstants(this);
            createMemberAccessGroup().generateConstants(this);
          });
      println("}");
      println();
    }

    private void generateUsesReflectionConstants() {
      println("public static final class UsesReflection {");
      withIndent(
          () -> {
            generateAnnotationConstants(UsesReflection.class);
            createDescriptionGroup().generateConstants(this);
            createConsequencesAsValueGroup().generateConstants(this);
            createAdditionalPreconditionsGroup().generateConstants(this);
          });
      println("}");
      println();
    }

    private void generateUsedByReflectionConstants() {
      println("public static final class UsedByReflection {");
      withIndent(
          () -> {
            generateAnnotationConstants(UsedByReflection.class);
            createDescriptionGroup().generateConstants(this);
            createPreconditionsGroup().generateConstants(this);
            createAdditionalTargetsGroup(".").generateConstants(this);
          });
      println("}");
      println();
    }

    private void generateUsedByNativeConstants() {
      println("public static final class UsedByNative {");
      withIndent(
          () -> {
            generateAnnotationConstants(UsedByNative.class);
            println("// Content is the same as " + simpleName(UsedByReflection.class) + ".");
          });
      println("}");
      println();
    }

    private void generateCheckRemovedConstants() {
      println("public static final class CheckRemoved {");
      withIndent(
          () -> {
            generateAnnotationConstants(CheckRemoved.class);
          });
      println("}");
      println();
    }

    private void generateCheckOptimizedOutConstants() {
      println("public static final class CheckOptimizedOut {");
      withIndent(
          () -> {
            generateAnnotationConstants(CheckOptimizedOut.class);
          });
      println("}");
      println();
    }

    private void generateItemConstants() {
      DocPrinter.printer()
          .setDocTitle("Item properties common to binding items, conditions and targets.")
          .printDoc(this::println);
      println("public static final class Item {");
      withIndent(
          () -> {
            // Bindings.
            createClassBindingGroup().generateConstants(this);
            createMemberBindingGroup().generateConstants(this);
            // Classes.
            createClassNamePatternGroup().generateConstants(this);
            createClassInstanceOfPatternGroup().generateConstants(this);
            createClassAnnotatedByPatternGroup().generateConstants(this);
            // Members.
            createMemberAnnotatedByGroup().generateConstants(this);
            createMemberAccessGroup().generateConstants(this);
            // Methods.
            createMethodAnnotatedByGroup().generateConstants(this);
            createMethodAccessGroup().generateConstants(this);
            createMethodNameGroup().generateConstants(this);
            createMethodReturnTypeGroup().generateConstants(this);
            createMethodParametersGroup().generateConstants(this);
            // Fields.
            createFieldAnnotatedByGroup().generateConstants(this);
            createFieldAccessGroup().generateConstants(this);
            createFieldNameGroup().generateConstants(this);
            createFieldTypeGroup().generateConstants(this);
          });
      println("}");
      println();
    }

    private void generateBindingConstants() {
      println("public static final class Binding {");
      withIndent(
          () -> {
            generateAnnotationConstants(KeepBinding.class);
            bindingName().generateConstants(this);
          });
      println("}");
      println();
    }

    private void generateConditionConstants() {
      println("public static final class Condition {");
      withIndent(
          () -> {
            generateAnnotationConstants(KeepCondition.class);
          });
      println("}");
      println();
    }

    private void generateTargetConstants() {
      println("public static final class Target {");
      withIndent(
          () -> {
            generateAnnotationConstants(KeepTarget.class);
            getKindGroup().generateConstants(this);
            getKeepConstraintsGroup().generateConstants(this);
          });
      println("}");
      println();
    }

    private void generateKindConstants() {
      println("public static final class Kind {");
      withIndent(
          () -> {
            generateAnnotationConstants(KeepItemKind.class);
            for (KeepItemKind value : KeepItemKind.values()) {
              if (value != KeepItemKind.DEFAULT) {
                println(
                    "public static final String "
                        + value.name()
                        + " = "
                        + quote(value.name())
                        + ";");
              }
            }
          });
      println("}");
      println();
    }

    private void generateConstraintConstants() {
      println("public static final class Constraints {");
      withIndent(
          () -> {
            generateAnnotationConstants(KeepConstraint.class);
            for (KeepConstraint value : KeepConstraint.values()) {
              println(
                  "public static final String " + value.name() + " = " + quote(value.name()) + ";");
            }
          });
      println("}");
      println();
    }

    private void generateOptionConstants() {
      println("public static final class Option {");
      withIndent(
          () -> {
            generateAnnotationConstants(KeepOption.class);
            for (KeepOption value : KeepOption.values()) {
              println(
                  "public static final String " + value.name() + " = " + quote(value.name()) + ";");
            }
          });
      println("}");
      println();
    }

    private boolean isMemberAccessProperty(String name) {
      for (MemberAccessFlags value : MemberAccessFlags.values()) {
        if (value.name().equals(name)) {
          return true;
        }
      }
      return false;
    }

    private void generateMemberAccessConstants() {
      println("public static final class MemberAccess {");
      withIndent(
          () -> {
            generateAnnotationConstants(MemberAccessFlags.class);
            println("public static final String NEGATION_PREFIX = \"NON_\";");
            for (MemberAccessFlags value : MemberAccessFlags.values()) {
              if (!value.name().startsWith("NON_")) {
                println(
                    "public static final String "
                        + value.name()
                        + " = "
                        + quote(value.name())
                        + ";");
              }
            }
          });
      println("}");
      println();
    }

    private void generateMethodAccessConstants() {
      println("public static final class MethodAccess {");
      withIndent(
          () -> {
            generateAnnotationConstants(MethodAccessFlags.class);
            for (MethodAccessFlags value : MethodAccessFlags.values()) {
              if (value.name().startsWith("NON_") || isMemberAccessProperty(value.name())) {
                continue;
              }
              println(
                  "public static final String " + value.name() + " = " + quote(value.name()) + ";");
            }
          });
      println("}");
      println();
    }

    private void generateFieldAccessConstants() {
      println("public static final class FieldAccess {");
      withIndent(
          () -> {
            generateAnnotationConstants(FieldAccessFlags.class);
            for (FieldAccessFlags value : FieldAccessFlags.values()) {
              if (value.name().startsWith("NON_") || isMemberAccessProperty(value.name())) {
                continue;
              }
              println(
                  "public static final String " + value.name() + " = " + quote(value.name()) + ";");
            }
          });
      println("}");
      println();
    }

    private void generateStringPatternConstants() {
      println("public static final class StringPattern {");
      withIndent(
          () -> {
            generateAnnotationConstants(StringPattern.class);
            stringPatternExactGroup().generateConstants(this);
            stringPatternPrefixGroup().generateConstants(this);
            stringPatternSuffixGroup().generateConstants(this);
          });
      println("}");
      println();
    }

    private void generateTypePatternConstants() {
      println("public static final class TypePattern {");
      withIndent(
          () -> {
            generateAnnotationConstants(TypePattern.class);
            typePatternGroup().generateConstants(this);
          });
      println("}");
      println();
    }

    private void generateClassNamePatternConstants() {
      println("public static final class ClassNamePattern {");
      withIndent(
          () -> {
            generateAnnotationConstants(ClassNamePattern.class);
            classNamePatternSimpleNameGroup().generateConstants(this);
            classNamePatternPackageGroup().generateConstants(this);
          });
      println("}");
      println();
    }

    private static void writeFile(Path file, Consumer<Generator> fn) throws IOException {
      ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
      PrintStream printStream = new PrintStream(byteStream);
      Generator generator = new Generator(printStream);
      fn.accept(generator);
      String formatted = byteStream.toString();
      if (file.toString().endsWith(".java")) {
        formatted = CodeGenerationBase.formatRawOutput(formatted);
      }
      Files.write(Paths.get(ToolHelper.getProjectRoot()).resolve(file), formatted.getBytes());
    }

    public static Path source(Path pkg, Class<?> clazz) {
      return pkg.resolve(simpleName(clazz) + ".java");
    }

    public static void run() throws IOException {
      writeFile(Paths.get("doc/keepanno-guide.md"), KeepAnnoMarkdownGenerator::generateMarkdownDoc);

      Path keepAnnoRoot = Paths.get("src/keepanno/java/com/android/tools/r8/keepanno");

      Path astPkg = keepAnnoRoot.resolve("ast");
      writeFile(source(astPkg, AnnotationConstants.class), Generator::generateConstants);

      Path annoPkg = Paths.get("src/keepanno/java/com/android/tools/r8/keepanno/annotations");
      writeFile(source(annoPkg, StringPattern.class), Generator::generateStringPattern);
      writeFile(source(annoPkg, TypePattern.class), Generator::generateTypePattern);
      writeFile(source(annoPkg, ClassNamePattern.class), Generator::generateClassNamePattern);
      writeFile(source(annoPkg, KeepBinding.class), Generator::generateKeepBinding);
      writeFile(source(annoPkg, KeepTarget.class), Generator::generateKeepTarget);
      writeFile(source(annoPkg, KeepCondition.class), Generator::generateKeepCondition);
      writeFile(source(annoPkg, KeepForApi.class), Generator::generateKeepForApi);
      writeFile(source(annoPkg, UsesReflection.class), Generator::generateUsesReflection);
      writeFile(
          source(annoPkg, UsedByReflection.class),
          g -> g.generateUsedByX("UsedByReflection", "accessed reflectively"));
      writeFile(
          source(annoPkg, UsedByNative.class),
          g -> g.generateUsedByX("UsedByNative", "accessed from native code via JNI"));
    }
  }

}
