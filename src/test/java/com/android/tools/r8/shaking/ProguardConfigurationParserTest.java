// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.graph.DexAccessFlags;
import com.android.tools.r8.graph.DexItemFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProguardConfigurationParserTest extends TestBase {

  private static final String VALID_PROGUARD_DIR = "src/test/proguard/valid/";
  private static final String INVALID_PROGUARD_DIR = "src/test/proguard/invalid/";
  private static final String PROGUARD_SPEC_FILE = VALID_PROGUARD_DIR + "proguard.flags";
  private static final String MULTIPLE_NAME_PATTERNS_FILE =
      VALID_PROGUARD_DIR + "multiple-name-patterns.flags";
  private static final String ACCESS_FLAGS_FILE = VALID_PROGUARD_DIR + "access-flags.flags";
  private static final String WHY_ARE_YOU_KEEPING_FILE =
      VALID_PROGUARD_DIR + "why-are-you-keeping.flags";
  private static final String ASSUME_NO_SIDE_EFFECTS =
      VALID_PROGUARD_DIR + "assume-no-side-effects.flags";
  private static final String ASSUME_NO_SIDE_EFFECTS_WITH_RETURN_VALUE =
      VALID_PROGUARD_DIR + "assume-no-side-effects-with-return-value.flags";
  private static final String ASSUME_VALUES_WITH_RETURN_VALUE =
      VALID_PROGUARD_DIR + "assume-values-with-return-value.flags";
  private static final String INCLUDING =
      VALID_PROGUARD_DIR + "including.flags";
  private static final String INVALID_INCLUDING_1 =
      INVALID_PROGUARD_DIR + "including-1.flags";
  private static final String INVALID_INCLUDING_2 =
      INVALID_PROGUARD_DIR + "including-2.flags";
  private static final String LIBRARY_JARS =
      VALID_PROGUARD_DIR + "library-jars.flags";
  private static final String SEEDS =
      VALID_PROGUARD_DIR + "seeds.flags";
  private static final String SEEDS_2 =
      VALID_PROGUARD_DIR + "seeds-2.flags";
  private static final String VERBOSE =
      VALID_PROGUARD_DIR + "verbose.flags";
  private static final String DONT_OBFUSCATE =
      VALID_PROGUARD_DIR + "dontobfuscate.flags";
  private static final String DONT_SKIP_NON_PUBLIC_LIBRARY_CLASSES =
      VALID_PROGUARD_DIR + "dontskipnonpubliclibraryclasses.flags";
  private static final String DONT_OPTIMIZE =
      VALID_PROGUARD_DIR + "dontoptimize.flags";
  private static final String SKIP_NON_PUBLIC_LIBRARY_CLASSES =
      VALID_PROGUARD_DIR + "skipnonpubliclibraryclasses.flags";
  private static final String PARSE_AND_SKIP_SINGLE_ARGUMENT =
      VALID_PROGUARD_DIR + "parse-and-skip-single-argument.flags";

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void parse() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(PROGUARD_SPEC_FILE));
    List<ProguardConfigurationRule> rules = parser.getConfig().getRules();
    assertEquals(24, rules.size());
    assertEquals(1, rules.get(0).getMemberRules().size());
  }

  @Test
  public void parseMultipleNamePatterns() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(MULTIPLE_NAME_PATTERNS_FILE));
    List<ProguardConfigurationRule> rules = parser.getConfig().getRules();
    assertEquals(1, rules.size());
    ProguardConfigurationRule rule = rules.get(0);
    assertEquals(1, rule.getMemberRules().size());
    assertEquals("com.company.hello.**", rule.getClassNames().get(0).toString());
    assertEquals("com.company.world.**", rule.getClassNames().get(1).toString());
    assertEquals(ProguardKeepRuleType.KEEP, ((ProguardKeepRule) rule).getType());
    assertTrue(rule.getInheritanceIsExtends());
    assertEquals("some.library.Class", rule.getInheritanceClassName().toString());
    ProguardMemberRule memberRule = rule.getMemberRules().iterator().next();
    assertTrue(memberRule.getAccessFlags().isProtected());
    assertEquals(ProguardNameMatcher.create("getContents"), memberRule.getName());
    assertEquals("java.lang.Object[][]", memberRule.getType().toString());
    assertEquals(ProguardMemberType.METHOD, memberRule.getRuleType());
    assertEquals(0, memberRule.getArguments().size());
  }

  @Test
  public void parseAccessFlags() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(ACCESS_FLAGS_FILE));
    List<ProguardConfigurationRule> rules = parser.getConfig().getRules();
    assertEquals(1, rules.size());
    ProguardConfigurationRule rule = rules.get(0);
    DexAccessFlags publicAndFinalFlags = new DexAccessFlags(0);
    publicAndFinalFlags.setPublic();
    publicAndFinalFlags.setFinal();
    assertTrue(rule.getClassAccessFlags().containsNoneOf(publicAndFinalFlags));
    assertTrue(rule.getNegatedClassAccessFlags().containsAllOf(publicAndFinalFlags));
    DexAccessFlags abstractFlags = new DexAccessFlags(0);
    abstractFlags.setAbstract();
    assertTrue(rule.getClassAccessFlags().containsAllOf(abstractFlags));
    assertTrue(rule.getNegatedClassAccessFlags().containsNoneOf(abstractFlags));
    for (ProguardMemberRule member : rule.getMemberRules()) {
      if (member.getRuleType() == ProguardMemberType.ALL_FIELDS) {
        DexAccessFlags publicFlags = new DexAccessFlags(0);
        publicAndFinalFlags.setPublic();
        assertTrue(member.getAccessFlags().containsAllOf(publicFlags));
        assertTrue(member.getNegatedAccessFlags().containsNoneOf(publicFlags));
        DexAccessFlags staticFlags = new DexAccessFlags(0);
        staticFlags.setStatic();
        assertTrue(member.getAccessFlags().containsNoneOf(staticFlags));
        assertTrue(member.getNegatedAccessFlags().containsAllOf(staticFlags));
      } else {
        assertTrue(member.getRuleType() == ProguardMemberType.ALL_METHODS);
        DexAccessFlags publicProtectedVolatileFlags = new DexAccessFlags(0);
        publicProtectedVolatileFlags.setPublic();
        publicProtectedVolatileFlags.setProtected();
        publicProtectedVolatileFlags.setVolatile();
        assertTrue(member.getAccessFlags().containsNoneOf(publicProtectedVolatileFlags));
        assertTrue(member.getNegatedAccessFlags().containsAllOf(publicProtectedVolatileFlags));
      }
    }
  }

  @Test
  public void parseWhyAreYouKeeping() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(WHY_ARE_YOU_KEEPING_FILE));
    List<ProguardConfigurationRule> rules = parser.getConfig().getRules();
    assertEquals(1, rules.size());
    ProguardConfigurationRule rule = rules.get(0);
    assertEquals(1, rule.getClassNames().size());
    assertEquals("*", rule.getClassNames().get(0).toString());
    assertTrue(rule.getInheritanceIsExtends());
    assertEquals("foo.bar", rule.getInheritanceClassName().toString());
  }

  @Test
  public void parseAssumeNoSideEffects() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(ASSUME_NO_SIDE_EFFECTS));
    List<ProguardConfigurationRule> assumeNoSideEffects = parser.getConfig().getRules();
    assertEquals(1, assumeNoSideEffects.size());
    assumeNoSideEffects.get(0).getMemberRules().forEach(rule -> {
      assertFalse(rule.hasReturnValue());
    });
  }

  @Test
  public void parseAssumeNoSideEffectsWithReturnValue()
      throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(ASSUME_NO_SIDE_EFFECTS_WITH_RETURN_VALUE));
    List<ProguardConfigurationRule> assumeNoSideEffects = parser.getConfig().getRules();
    assertEquals(1, assumeNoSideEffects.size());
    assumeNoSideEffects.get(0).getMemberRules().forEach(rule -> {
      assertTrue(rule.hasReturnValue());
      if (rule.getName().matches("returnsTrue") || rule.getName().matches("returnsFalse")) {
        assertTrue(rule.getReturnValue().isBoolean());
        assertFalse(rule.getReturnValue().isValueRange());
        assertFalse(rule.getReturnValue().isField());
        assertEquals(rule.getName().matches("returnsTrue"), rule.getReturnValue().getBoolean());
      } else if (rule.getName().matches("returns1")) {
        assertFalse(rule.getReturnValue().isBoolean());
        assertTrue(rule.getReturnValue().isValueRange());
        assertFalse(rule.getReturnValue().isField());
        assertTrue(rule.getReturnValue().isSingleValue());
        assertEquals(1, rule.getReturnValue().getValueRange().getMin());
        assertEquals(1, rule.getReturnValue().getValueRange().getMax());
        assertEquals(1, rule.getReturnValue().getSingleValue());
      } else if (rule.getName().matches("returns2To4")) {
        assertFalse(rule.getReturnValue().isBoolean());
        assertTrue(rule.getReturnValue().isValueRange());
        assertFalse(rule.getReturnValue().isField());
        assertFalse(rule.getReturnValue().isSingleValue());
        assertEquals(2, rule.getReturnValue().getValueRange().getMin());
        assertEquals(4, rule.getReturnValue().getValueRange().getMax());
      } else if (rule.getName().matches("returnsField")) {
        assertFalse(rule.getReturnValue().isBoolean());
        assertFalse(rule.getReturnValue().isValueRange());
        assertTrue(rule.getReturnValue().isField());
        assertEquals("com.google.C", rule.getReturnValue().getField().clazz.toString());
        assertEquals("int", rule.getReturnValue().getField().type.toString());
        assertEquals("X", rule.getReturnValue().getField().name.toString());
      }
    });
  }

  @Test
  public void parseAssumeValuesWithReturnValue()
      throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(ASSUME_VALUES_WITH_RETURN_VALUE));
    List<ProguardConfigurationRule> assumeValues = parser.getConfig().getRules();
    assertEquals(1, assumeValues.size());
    assumeValues.get(0).getMemberRules().forEach(rule -> {
      assertTrue(rule.hasReturnValue());
      if (rule.getName().matches("isTrue") || rule.getName().matches("isFalse")) {
        assertTrue(rule.getReturnValue().isBoolean());
        assertFalse(rule.getReturnValue().isValueRange());
        assertFalse(rule.getReturnValue().isField());
        assertEquals(rule.getName().matches("isTrue"), rule.getReturnValue().getBoolean());
      } else if (rule.getName().matches("is1")) {
        assertFalse(rule.getReturnValue().isBoolean());
        assertTrue(rule.getReturnValue().isValueRange());
        assertFalse(rule.getReturnValue().isField());
        assertTrue(rule.getReturnValue().isSingleValue());
        assertEquals(1, rule.getReturnValue().getValueRange().getMin());
        assertEquals(1, rule.getReturnValue().getValueRange().getMax());
        assertEquals(1, rule.getReturnValue().getSingleValue());
      } else if (rule.getName().matches("is2To4")) {
        assertFalse(rule.getReturnValue().isBoolean());
        assertTrue(rule.getReturnValue().isValueRange());
        assertFalse(rule.getReturnValue().isField());
        assertFalse(rule.getReturnValue().isSingleValue());
        assertEquals(2, rule.getReturnValue().getValueRange().getMin());
        assertEquals(4, rule.getReturnValue().getValueRange().getMax());
      } else if (rule.getName().matches("isField")) {
        assertFalse(rule.getReturnValue().isBoolean());
        assertFalse(rule.getReturnValue().isValueRange());
        assertTrue(rule.getReturnValue().isField());
        assertEquals("com.google.C", rule.getReturnValue().getField().clazz.toString());
        assertEquals("int", rule.getReturnValue().getField().type.toString());
        assertEquals("X", rule.getReturnValue().getField().name.toString());
      }
    });
  }

  @Test
  public void parseDontobfuscate() throws IOException, ProguardRuleParserException {
    new ProguardConfigurationParser(new DexItemFactory()).parse(Paths.get(DONT_OBFUSCATE));
  }

  @Test
  public void parseIncluding() throws IOException, ProguardRuleParserException {
    new ProguardConfigurationParser(new DexItemFactory()).parse(Paths.get(INCLUDING));
  }

  @Test
  public void parseInvalidIncluding1() throws IOException {
    try {
      new ProguardConfigurationParser(new DexItemFactory()).parse(Paths.get(INVALID_INCLUDING_1));
      fail();
    } catch (ProguardRuleParserException e) {
      assertTrue(e.getMessage().contains("6")); // line
      assertTrue(e.getMessage().contains("including-1.flags")); // file in error
      assertTrue(e.getMessage().contains("does-not-exist.flags")); // missing file
    }
  }

  @Test
  public void parseInvalidIncluding2() throws IOException {
    try {
      new ProguardConfigurationParser(new DexItemFactory()).parse(Paths.get(INVALID_INCLUDING_2));
      fail();
    } catch (ProguardRuleParserException e) {
      String message = e.getMessage();
      assertTrue(message, message.contains("6")); // line
      assertTrue(message, message.contains("including-2.flags")); // file in error
      assertTrue(message, message.contains("does-not-exist.flags")); // missing file
    }
  }

  @Test
  public void parseLibraryJars() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(LIBRARY_JARS));
    assertEquals(4, parser.getConfig().getLibraryjars().size());
  }

  @Test
  public void parseSeeds() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(SEEDS));
    ProguardConfiguration config = parser.getConfig();
    assertTrue(config.getPrintSeeds());
    assertNull(config.getSeedFile());
  }

  @Test
  public void parseSeeds2() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(SEEDS_2));
    ProguardConfiguration config = parser.getConfig();
    assertTrue(config.getPrintSeeds());
    assertNotNull(config.getSeedFile());
  }

  @Test
  public void parseVerbose() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(VERBOSE));
    ProguardConfiguration config = parser.getConfig();
    assertTrue(config.isVerbose());
  }

  @Test
  public void parseDontSkipNonPublicLibraryClasses()
      throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(DONT_SKIP_NON_PUBLIC_LIBRARY_CLASSES));
  }

  @Test
  public void parseDontOptimize()
      throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(DONT_OPTIMIZE));
  }

  @Test
  public void parseSkipNonPublicLibraryClasses()
      throws IOException, ProguardRuleParserException {
    thrown.expect(ProguardRuleParserException.class);
    thrown.expectMessage("Unsupported option: -skipnonpubliclibraryclasses");
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(SKIP_NON_PUBLIC_LIBRARY_CLASSES));
  }

  @Test
  public void parseAndskipSingleArgument() throws IOException, ProguardRuleParserException {
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    parser.parse(Paths.get(PARSE_AND_SKIP_SINGLE_ARGUMENT));
  }

  @Test
  public void parseInvalidKeepClassOption() throws IOException, ProguardRuleParserException {
    thrown.expect(ProguardRuleParserException.class);
    thrown.expectMessage("Unknown option at ");
    ProguardConfigurationParser parser = new ProguardConfigurationParser(new DexItemFactory());
    Path proguardConfig = writeTextToTempFile(
        "-keepclassx public class * {  ",
        "  native <methods>;           ",
        "}                             "
    );
    parser.parse(proguardConfig);
  }
}
