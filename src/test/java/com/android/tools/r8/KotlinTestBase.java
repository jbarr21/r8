// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import static org.hamcrest.CoreMatchers.containsString;

import com.android.tools.r8.KotlinCompilerTool.KotlinCompiler;
import com.android.tools.r8.TestRuntime.CfRuntime;
import com.android.tools.r8.ToolHelper.KotlinTargetVersion;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.hamcrest.Matcher;

public abstract class KotlinTestBase extends TestBase {

  protected  static final String checkParameterIsNotNullSignature =
      "void kotlin.jvm.internal.Intrinsics.checkParameterIsNotNull("
          + "java.lang.Object, java.lang.String)";
  protected static final String throwParameterIsNotNullExceptionSignature =
      "void kotlin.jvm.internal.Intrinsics.throwParameterIsNullException(java.lang.String)";
  public static final String METADATA_DESCRIPTOR = "Lkotlin/Metadata;";
  public static final String METADATA_TYPE =
      DescriptorUtils.descriptorToJavaType(METADATA_DESCRIPTOR);

  private static final String RSRC = "kotlinR8TestResources";

  protected final KotlinCompiler kotlinc;
  protected final KotlinTargetVersion targetVersion;

  protected KotlinTestBase(KotlinTargetVersion targetVersion, KotlinCompiler kotlinc) {
    this.targetVersion = targetVersion;
    this.kotlinc = kotlinc;
  }

  protected static List<Path> getKotlinFilesInTestPackage(Package pkg) throws IOException {
    String folder = DescriptorUtils.getBinaryNameFromJavaType(pkg.getName());
    return Files.walk(Paths.get(ToolHelper.TESTS_DIR, "java", folder))
        .filter(path -> path.toString().endsWith(".kt"))
        .collect(Collectors.toList());
  }

  protected static Path getKotlinFileInTestPackage(Package pkg, String fileName) {
    String folder = DescriptorUtils.getBinaryNameFromJavaType(pkg.getName());
    return getKotlinFileInTest(folder, fileName);
  }

  protected static Path getKotlinFileInTest(String folder, String fileName) {
    return Paths.get(ToolHelper.TESTS_DIR, "java", folder, fileName + FileUtils.KT_EXTENSION);
  }

  protected static Path getKotlinFileInResource(String folder, String fileName) {
    return Paths.get(ToolHelper.TESTS_DIR, RSRC, folder, fileName + FileUtils.KT_EXTENSION);
  }

  protected Path getKotlinJarFile(String folder) {
    return Paths.get(ToolHelper.TESTS_BUILD_DIR, RSRC,
        targetVersion.getFolderName(), folder + FileUtils.JAR_EXTENSION);
  }

  protected Path getJavaJarFile(String folder) {
    return Paths.get(ToolHelper.TESTS_BUILD_DIR, RSRC,
        targetVersion.getFolderName(), folder + ".java" + FileUtils.JAR_EXTENSION);
  }

  protected Path getMappingfile(String folder, String mappingFileName) {
    return Paths.get(ToolHelper.TESTS_DIR, RSRC, folder, mappingFileName);
  }

  protected static Matcher<String> expectedInfoMessagesFromKotlinStdLib() {
    return containsString("No VersionRequirement");
  }

  protected KotlinCompilerTool kotlinCompilerTool() {
    return KotlinCompilerTool.create(CfRuntime.getCheckedInJdk9(), temp, kotlinc, targetVersion);
  }

  public static KotlinCompileMemoizer getCompileMemoizer(Path... source) {
    return new KotlinCompileMemoizer(Arrays.asList(source));
  }

  public static KotlinCompileMemoizer getCompileMemoizer(Collection<Path> sources) {
    return new KotlinCompileMemoizer(sources);
  }

  public static class KotlinCompileMemoizer {

    private final Collection<Path> sources;
    private Consumer<KotlinCompilerTool> kotlinCompilerToolConsumer = x -> {};
    private final Map<KotlinCompiler, Map<KotlinTargetVersion, Path>> compiledPaths =
        new IdentityHashMap<>();

    public KotlinCompileMemoizer(Collection<Path> sources) {
      this.sources = sources;
    }

    public KotlinCompileMemoizer configure(Consumer<KotlinCompilerTool> consumer) {
      this.kotlinCompilerToolConsumer = consumer;
      return this;
    }

    public Path getForConfiguration(KotlinCompiler compiler, KotlinTargetVersion targetVersion) {
      Map<KotlinTargetVersion, Path> kotlinTargetVersionPathMap = compiledPaths.get(compiler);
      if (kotlinTargetVersionPathMap == null) {
        kotlinTargetVersionPathMap = new IdentityHashMap<>();
        compiledPaths.put(compiler, kotlinTargetVersionPathMap);
      }
      return kotlinTargetVersionPathMap.computeIfAbsent(
          targetVersion,
          ignored -> {
            try {
              return kotlinc(compiler, targetVersion)
                  .addSourceFiles(sources)
                  .apply(kotlinCompilerToolConsumer)
                  .compile();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }
  }
}
