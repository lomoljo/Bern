// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.java;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.Util;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.rules.cpp.CppCompilationContext;
import com.google.devtools.build.lib.rules.cpp.LinkerInput;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgs.ClasspathType;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesCollector.LocalMetadataCollector;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesProvider;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A helper class to create configured targets for Java rules.
 */
public class JavaCommon {
  private static final Function<TransitiveInfoCollection, Label> GET_COLLECTION_LABEL =
      new Function<TransitiveInfoCollection, Label>() {
        @Override
        public Label apply(TransitiveInfoCollection collection) {
          return collection.getLabel();
        }
      };

  public static final InstrumentationSpec JAVA_COLLECTION_SPEC = new InstrumentationSpec(
      FileTypeSet.of(JavaSemantics.JAVA_SOURCE))
      .withSourceAttributes("srcs")
      .withDependencyAttributes("deps", "data", "exports", "runtime_deps", "binary_under_test");

  /**
   * Collects all metadata files generated by Java compilation actions.
   */
  private static final LocalMetadataCollector JAVA_METADATA_COLLECTOR =
      new LocalMetadataCollector() {
    @Override
    public void collectMetadataArtifacts(Iterable<Artifact> objectFiles,
        AnalysisEnvironment analysisEnvironment, NestedSetBuilder<Artifact> metadataFilesBuilder) {
      for (Artifact artifact : objectFiles) {
        Action action = analysisEnvironment.getLocalGeneratingAction(artifact);
        if (action instanceof JavaCompileAction) {
          addOutputs(metadataFilesBuilder, action, JavaSemantics.COVERAGE_METADATA);
        }
      }
    }
  };

  private ClasspathConfiguredFragment classpathFragment = new ClasspathConfiguredFragment();
  private JavaCompilationArtifacts javaArtifacts = JavaCompilationArtifacts.EMPTY;
  private ImmutableList<String> javacOpts;

  // Targets treated as deps in compilation time, runtime time and both
  private final ImmutableMap<ClasspathType, ImmutableList<TransitiveInfoCollection>>
      targetsTreatedAsDeps;

  private ImmutableList<Artifact> sources = ImmutableList.of();
  private ImmutableList<JavaPluginInfoProvider> activePlugins = ImmutableList.of();

  private final RuleContext ruleContext;
  private final JavaSemantics semantics;
  private JavaCompilationHelper javaCompilationHelper;

  public JavaCommon(RuleContext ruleContext, JavaSemantics semantics) {
    this(ruleContext, semantics,
        collectTargetsTreatedAsDeps(ruleContext, semantics, ClasspathType.COMPILE_ONLY),
        collectTargetsTreatedAsDeps(ruleContext, semantics, ClasspathType.RUNTIME_ONLY),
        collectTargetsTreatedAsDeps(ruleContext, semantics, ClasspathType.BOTH));
  }

  public JavaCommon(RuleContext ruleContext,
      JavaSemantics semantics,
      ImmutableList<TransitiveInfoCollection> compileDeps,
      ImmutableList<TransitiveInfoCollection> runtimeDeps,
      ImmutableList<TransitiveInfoCollection> bothDeps) {
    this.ruleContext = ruleContext;
    this.semantics = semantics;
    this.targetsTreatedAsDeps = ImmutableMap.of(
        ClasspathType.COMPILE_ONLY, compileDeps,
        ClasspathType.RUNTIME_ONLY, runtimeDeps,
        ClasspathType.BOTH, bothDeps);
  }

  /**
   * Validates that the packages listed under "deps" all have the given constraint. If a package
   * does not have this attribute, an error is generated.
   */
  public static final void validateConstraint(RuleContext ruleContext,
      String constraint, Iterable<? extends TransitiveInfoCollection> targets) {
    for (JavaConstraintProvider constraintProvider :
        AnalysisUtils.getProviders(targets, JavaConstraintProvider.class)) {
      if (!constraintProvider.getJavaConstraints().contains(constraint)) {
        ruleContext.attributeError("deps",
            String.format("%s: does not have constraint '%s'",
                constraintProvider.getLabel(), constraint));
      }
    }
  }

  /**
   * Creates an action to aggregate all metadata artifacts into a single
   * &lt;target_name&gt;_instrumented.jar file.
   */
  public static void createInstrumentedJarAction(RuleContext ruleContext, JavaSemantics semantics,
      List<Artifact> metadataArtifacts, Artifact instrumentedJar, String mainClass) {
    // In Jacoco's setup, metadata artifacts are real jars.
    new DeployArchiveBuilder(semantics, ruleContext)
        .setOutputJar(instrumentedJar)
        // We need to save the original mainClass because we're going to run inside CoverageRunner
        .setJavaStartClass(mainClass)
        .setAttributes(new JavaTargetAttributes.Builder(semantics).build())
        .addRuntimeJars(ImmutableList.copyOf(metadataArtifacts))
        .setCompression(DeployArchiveBuilder.Compression.UNCOMPRESSED)
        .build();
  }

  public static ImmutableList<String> getConstraints(RuleContext ruleContext) {
    return ruleContext.getRule().isAttrDefined("constraints", Type.STRING_LIST)
        ? ImmutableList.copyOf(ruleContext.attributes().get("constraints", Type.STRING_LIST))
        : ImmutableList.<String>of();
  }

  public void setClassPathFragment(ClasspathConfiguredFragment classpathFragment) {
    this.classpathFragment = classpathFragment;
  }

  public void setJavaCompilationArtifacts(JavaCompilationArtifacts javaArtifacts) {
    this.javaArtifacts = javaArtifacts;
  }

  public JavaCompilationArtifacts getJavaCompilationArtifacts() {
    return javaArtifacts;
  }

  public ImmutableList<Artifact> getProcessorClasspathJars() {
    Set<Artifact> processorClasspath = new LinkedHashSet<>();
    for (JavaPluginInfoProvider plugin : activePlugins) {
      for (Artifact classpathJar : plugin.getProcessorClasspath()) {
        processorClasspath.add(classpathJar);
      }
    }
    return ImmutableList.copyOf(processorClasspath);
  }

  public ImmutableList<String> getProcessorClassNames() {
    Set<String> processorNames = new LinkedHashSet<>();
    for (JavaPluginInfoProvider plugin : activePlugins) {
      processorNames.addAll(plugin.getProcessorClasses());
    }
    return ImmutableList.copyOf(processorNames);
  }

  /**
   * Creates the java.library.path from a list of the native libraries.
   * Concatenates the parent directories of the shared libraries into a Java
   * search path. Each relative path entry is prepended with "${JAVA_RUNFILES}/"
   * so it can be resolved at runtime.
   *
   * @param sharedLibraries a collection of native libraries to create the java
   *        library path from
   * @return a String containing the ":" separated java library path
   */
  public static String javaLibraryPath(
      Collection<Artifact> sharedLibraries, String runfilePrefix) {
    StringBuilder buffer = new StringBuilder();
    Set<PathFragment> entries = new HashSet<>();
    for (Artifact sharedLibrary : sharedLibraries) {
      PathFragment entry = sharedLibrary.getRootRelativePath().getParentDirectory();
      if (entries.add(entry)) {
        if (buffer.length() > 0) {
          buffer.append(':');
        }
        buffer.append("${JAVA_RUNFILES}/" + runfilePrefix + "/");
        buffer.append(entry.getPathString());
      }
    }
    return buffer.toString();
  }

  /**
   * Collects Java compilation arguments for this target.
   *
   * @param recursive Whether to scan dependencies recursively.
   * @param isNeverLink Whether the target has the 'neverlink' attr.
   */
  JavaCompilationArgs collectJavaCompilationArgs(boolean recursive, boolean isNeverLink,
      Iterable<SourcesJavaCompilationArgsProvider> compilationArgsFromSources) {
    ClasspathType type = isNeverLink ? ClasspathType.COMPILE_ONLY : ClasspathType.BOTH;
    JavaCompilationArgs.Builder builder = JavaCompilationArgs.builder()
        .merge(getJavaCompilationArtifacts(), isNeverLink)
        .addTransitiveTargets(getExports(ruleContext), recursive, type);
    if (recursive) {
      builder
          .addTransitiveTargets(targetsTreatedAsDeps(ClasspathType.COMPILE_ONLY), recursive, type)
          .addTransitiveTargets(getRuntimeDeps(ruleContext), recursive, ClasspathType.RUNTIME_ONLY)
          .addSourcesTransitiveCompilationArgs(compilationArgsFromSources, recursive, type);
    }
    return builder.build();
  }

  /**
   * Collects Java dependency artifacts for this target.
   *
   * @param outDeps output (compile-time) dependency artifact of this target
   */
  NestedSet<Artifact> collectCompileTimeDependencyArtifacts(Artifact outDeps) {
    NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
    if (outDeps != null) {
      builder.add(outDeps);
    }

    for (JavaCompilationArgsProvider provider : AnalysisUtils.getProviders(
        getExports(ruleContext), JavaCompilationArgsProvider.class)) {
      builder.addTransitive(provider.getCompileTimeJavaDependencyArtifacts());
    }
    return builder.build();
  }

  public static List<TransitiveInfoCollection> getExports(RuleContext ruleContext) {
    // We need to check here because there are classes inheriting from this class that implement
    // rules that don't have this attribute.
    if (ruleContext.attributes().has("exports", BuildType.LABEL_LIST)) {
      return ImmutableList.copyOf(ruleContext.getPrerequisites("exports", Mode.TARGET));
    } else {
      return ImmutableList.of();
    }
  }

  /**
   * Sanity checks the given runtime dependencies, and emits errors if there is a problem.
   * Also called by {@link #initCommon()} for the current target's runtime dependencies.
   */
  public void checkRuntimeDeps(List<TransitiveInfoCollection> runtimeDepInfo) {
    for (TransitiveInfoCollection c : runtimeDepInfo) {
      JavaNeverlinkInfoProvider neverLinkedness =
          c.getProvider(JavaNeverlinkInfoProvider.class);
      if (neverLinkedness == null) {
        continue;
      }
      boolean reportError = !ruleContext.getConfiguration().getAllowRuntimeDepsOnNeverLink();
      if (neverLinkedness.isNeverlink()) {
        String msg = String.format("neverlink dep %s not allowed in runtime deps", c.getLabel());
        if (reportError) {
          ruleContext.attributeError("runtime_deps", msg);
        } else {
          ruleContext.attributeWarning("runtime_deps", msg);
        }
      }
    }
  }

  /**
   * Returns transitive Java native libraries.
   *
   * @see JavaNativeLibraryProvider
   */
  protected NestedSet<LinkerInput> collectTransitiveJavaNativeLibraries() {
    NativeLibraryNestedSetBuilder builder = new NativeLibraryNestedSetBuilder();
    builder.addJavaTargets(targetsTreatedAsDeps(ClasspathType.BOTH));

    if (ruleContext.getRule().isAttrDefined("data", BuildType.LABEL_LIST)) {
      builder.addJavaTargets(ruleContext.getPrerequisites("data", Mode.DATA));
    }
    return builder.build();
  }

  /**
   * Collects transitive source jars for the current rule.
   *
   * @param targetSrcJar The source jar artifact corresponding to the output of the current rule.
   * @return A nested set containing all of the source jar artifacts on which the current rule
   *         transitively depends.
   */
  public NestedSet<Artifact> collectTransitiveSourceJars(Artifact targetSrcJar) {
    NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();

    builder.add(targetSrcJar);
    for (JavaSourceJarsProvider dep : getDependencies(JavaSourceJarsProvider.class)) {
      builder.addTransitive(dep.getTransitiveSourceJars());
    }
    return builder.build();
  }

  /**
   * Collects transitive gen jars for the current rule.
   */
  private JavaGenJarsProvider collectTransitiveGenJars(
          boolean usesAnnotationProcessing,
          @Nullable Artifact genClassJar,
          @Nullable Artifact genSourceJar) {
    NestedSetBuilder<Artifact> classJarsBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> sourceJarsBuilder = NestedSetBuilder.stableOrder();

    if (genClassJar != null) {
      classJarsBuilder.add(genClassJar);
    }
    if (genSourceJar != null) {
      sourceJarsBuilder.add(genSourceJar);
    }
    for (JavaGenJarsProvider dep : getDependencies(JavaGenJarsProvider.class)) {
      classJarsBuilder.addTransitive(dep.getTransitiveGenClassJars());
      sourceJarsBuilder.addTransitive(dep.getTransitiveGenSourceJars());
    }
    return new JavaGenJarsProvider(
        usesAnnotationProcessing,
        genClassJar, 
        genSourceJar,
        classJarsBuilder.build(), 
        sourceJarsBuilder.build()
    );
  }

 /**
   * Collects transitive C++ dependencies.
   */
  protected CppCompilationContext collectTransitiveCppDeps() {
    CppCompilationContext.Builder builder = new CppCompilationContext.Builder(ruleContext);
    for (TransitiveInfoCollection dep : targetsTreatedAsDeps(ClasspathType.BOTH)) {
      CppCompilationContext context =
          dep.getProvider(CppCompilationContext.class);
      if (context != null) {
        builder.mergeDependentContext(context);
      }
    }
    return builder.build();
  }

  /**
   * Collects labels of targets and artifacts reached transitively via the "exports" attribute.
   */
  protected NestedSet<Label> collectTransitiveExports() {
    NestedSetBuilder<Label> builder = NestedSetBuilder.stableOrder();
    List<TransitiveInfoCollection> currentRuleExports = getExports(ruleContext);

    builder.addAll(Iterables.transform(currentRuleExports, GET_COLLECTION_LABEL));

    for (TransitiveInfoCollection dep : currentRuleExports) {
      JavaExportsProvider exportsProvider = dep.getProvider(JavaExportsProvider.class);

      if (exportsProvider != null) {
        builder.addTransitive(exportsProvider.getTransitiveExports());
      }
    }

    return builder.build();
  }

  public final void initializeJavacOpts() {
    initializeJavacOpts(semantics.getExtraJavacOpts(ruleContext));
  }

  public final void initializeJavacOpts(Iterable<String> extraJavacOpts) {
    javacOpts =  ImmutableList.copyOf(Iterables.concat(
        JavaToolchainProvider.getDefaultJavacOptions(ruleContext),
        ruleContext.getTokenizedStringListAttr("javacopts"), extraJavacOpts));
  }

  /**
   * Returns the string that the stub should use to determine the JVM
   * @param launcher if non-null, the cc_binary used to launch the Java Virtual Machine
   */
  public String getJavaBinSubstitution(@Nullable Artifact launcher) {
    Preconditions.checkState(ruleContext.getConfiguration().hasFragment(Jvm.class));
    PathFragment javaExecutable;

    if (launcher != null) {
      javaExecutable = launcher.getRootRelativePath();
    } else {
      javaExecutable = ruleContext.getFragment(Jvm.class).getJavaExecutable();
    }

    String pathPrefix = javaExecutable.isAbsolute() ? "" : "${JAVA_RUNFILES}/"
        + ruleContext.getRule().getWorkspaceName() + "/";
    return "JAVABIN=${JAVABIN:-" + pathPrefix + javaExecutable.getPathString() + "}";
  }

  /**
   * Heuristically determines the name of the primary Java class for this
   * executable, based on the rule name and the "srcs" list.
   *
   * <p>(This is expected to be the class containing the "main" method for a
   * java_binary, or a JUnit Test class for a java_test.)
   *
   * @param sourceFiles the source files for this rule
   * @return a fully qualified Java class name, or null if none could be
   *   determined.
   */
  public String determinePrimaryClass(Collection<Artifact> sourceFiles) {
    if (!sourceFiles.isEmpty()) {
      String mainSource = ruleContext.getTarget().getName() + ".java";
      for (Artifact sourceFile : sourceFiles) {
        PathFragment path = sourceFile.getRootRelativePath();
        if (path.getBaseName().equals(mainSource)) {
          return JavaUtil.getJavaFullClassname(FileSystemUtils.removeExtension(path));
        }
      }
    }
    // Last resort: Use the name and package name of the target.
    // TODO(bazel-team): this should be fixed to use a source file from the dependencies to
    // determine the package of the Java class.
    return JavaUtil.getJavaFullClassname(Util.getWorkspaceRelativePath(ruleContext.getTarget()));
  }

  /**
   * Gets the value of the "jvm_flags" attribute combining it with the default
   * options and expanding any make variables.
   */
  public List<String> getJvmFlags() {
    List<String> jvmFlags = new ArrayList<>();
    jvmFlags.addAll(ruleContext.getFragment(JavaConfiguration.class).getDefaultJvmFlags());
    jvmFlags.addAll(ruleContext.expandedMakeVariablesList("jvm_flags"));
    return jvmFlags;
  }

  private static List<TransitiveInfoCollection> getRuntimeDeps(RuleContext ruleContext) {
    // We need to check here because there are classes inheriting from this class that implement
    // rules that don't have this attribute.
    if (ruleContext.attributes().has("runtime_deps", BuildType.LABEL_LIST)) {
      return ImmutableList.copyOf(ruleContext.getPrerequisites("runtime_deps", Mode.TARGET));
    } else {
      return ImmutableList.of();
    }
  }

  public JavaTargetAttributes.Builder initCommon() {
    return initCommon(Collections.<Artifact>emptySet());
  }

  /**
   * Initialize the common actions and build various collections of artifacts
   * for the initializationHook() methods of the subclasses.
   *
   * <p>Note that not all subclasses call this method.
   *
   * @return the processed attributes
   */
  public JavaTargetAttributes.Builder initCommon(Collection<Artifact> extraSrcs) {
    Preconditions.checkState(javacOpts != null);
    sources = ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET).list();
    activePlugins = collectPlugins();

    JavaTargetAttributes.Builder javaTargetAttributes = new JavaTargetAttributes.Builder(semantics);
    javaCompilationHelper = new JavaCompilationHelper(
        ruleContext, semantics, javacOpts, javaTargetAttributes);

    processSrcs(javaCompilationHelper, javaTargetAttributes);
    javaTargetAttributes.addSourceArtifacts(extraSrcs);
    processRuntimeDeps(javaTargetAttributes);

    semantics.commonDependencyProcessing(ruleContext, javaTargetAttributes,
        targetsTreatedAsDeps(ClasspathType.COMPILE_ONLY));

    if (!ruleContext.getFragment(JavaConfiguration.class).allowPrecompiledJarsInSrcs()
        && javaTargetAttributes.hasJarFiles()) {
      ruleContext.attributeError(
          "srcs", "precompiled jars are not allowed as sources; use java_import instead?");
    }

    // Check that we have do not have both sources and jars.
    if ((javaTargetAttributes.hasSourceFiles() || javaTargetAttributes.hasSourceJars())
        && javaTargetAttributes.hasJarFiles()) {
      ruleContext.attributeWarning("srcs", "cannot use both Java sources - source "
          + "jars or source files - and precompiled jars");
    }

    if (disallowDepsWithoutSrcs(ruleContext.getRule().getRuleClass())
        && ruleContext.attributes().get("srcs", BuildType.LABEL_LIST).isEmpty()
        && ruleContext.getRule().isAttributeValueExplicitlySpecified("deps")) {
      ruleContext.attributeError("deps", "deps not allowed without srcs; move to runtime_deps?");
    }

    for (Artifact resource : semantics.collectResources(ruleContext)) {
      javaTargetAttributes.addResource(
          JavaHelper.getJavaResourcePath(semantics, ruleContext, resource), resource);
    }

    addPlugins(javaTargetAttributes);

    javaTargetAttributes.setRuleKind(ruleContext.getRule().getRuleClass());
    javaTargetAttributes.setTargetLabel(ruleContext.getLabel());

    return javaTargetAttributes;
  }

  private boolean disallowDepsWithoutSrcs(String ruleClass) {
    return ruleClass.equals("java_library")
        || ruleClass.equals("java_binary")
        || ruleClass.equals("java_test");
  }

  public ImmutableList<? extends TransitiveInfoCollection> targetsTreatedAsDeps(
      ClasspathType type) {
    return targetsTreatedAsDeps.get(type);
  }

  private static ImmutableList<TransitiveInfoCollection> collectTargetsTreatedAsDeps(
      RuleContext ruleContext, JavaSemantics semantics, ClasspathType type) {
    ImmutableList.Builder<TransitiveInfoCollection> builder = new Builder<>();

    if (!type.equals(ClasspathType.COMPILE_ONLY)) {
      builder.addAll(getRuntimeDeps(ruleContext));
      builder.addAll(getExports(ruleContext));
    }
    builder.addAll(ruleContext.getPrerequisites("deps", Mode.TARGET));

    semantics.collectTargetsTreatedAsDeps(ruleContext, builder);

    // Implicitly add dependency on java launcher cc_binary when --java_launcher= is enabled,
    // or when launcher attribute is specified in a build rule.
    TransitiveInfoCollection launcher = JavaHelper.launcherForTarget(semantics, ruleContext);
    if (launcher != null) {
      builder.add(launcher);
    }

    return builder.build();
  }

  public void addTransitiveInfoProviders(RuleConfiguredTargetBuilder builder,
      NestedSet<Artifact> filesToBuild, @Nullable Artifact classJar) {
    InstrumentedFilesProvider instrumentedFilesProvider = InstrumentedFilesCollector.collect(
        ruleContext, JAVA_COLLECTION_SPEC, JAVA_METADATA_COLLECTOR,
        filesToBuild, /*withBaselineCoverage*/!TargetUtils.isTestRule(ruleContext.getTarget()));

    builder
        .add(InstrumentedFilesProvider.class, instrumentedFilesProvider)
        .add(JavaExportsProvider.class, new JavaExportsProvider(collectTransitiveExports()))
        .addOutputGroup(OutputGroupProvider.FILES_TO_COMPILE, getFilesToCompile(classJar));
  }

  public void addGenJarsProvider(RuleConfiguredTargetBuilder builder,
      @Nullable Artifact genClassJar, @Nullable Artifact genSourceJar) {
    JavaGenJarsProvider genJarsProvider = collectTransitiveGenJars(
        javaCompilationHelper.usesAnnotationProcessing(),
        genClassJar, genSourceJar);

    NestedSetBuilder<Artifact> genJarsBuilder = NestedSetBuilder.stableOrder();
    genJarsBuilder.addTransitive(genJarsProvider.getTransitiveGenClassJars());
    genJarsBuilder.addTransitive(genJarsProvider.getTransitiveGenSourceJars());

    builder
        .add(JavaGenJarsProvider.class, genJarsProvider)
        .addOutputGroup(JavaSemantics.GENERATED_JARS_OUTPUT_GROUP, genJarsBuilder.build());
  }


  /**
   * Processes the sources of this target, adding them as messages, proper
   * sources or to the list of targets treated as deps as required.
   */
  private void processSrcs(JavaCompilationHelper helper,
      JavaTargetAttributes.Builder attributes) {
    for (MessageBundleProvider srcItem : ruleContext.getPrerequisites(
        "srcs", Mode.TARGET, MessageBundleProvider.class)) {
      attributes.addMessages(srcItem.getMessages());
    }

    attributes.addSourceArtifacts(sources);

    addCompileTimeClassPathEntriesMaybeThroughIjar(helper, attributes);
  }

  /**
   * Processes the transitive runtime_deps of this target.
   */
  private void processRuntimeDeps(JavaTargetAttributes.Builder attributes) {
    List<TransitiveInfoCollection> runtimeDepInfo = getRuntimeDeps(ruleContext);
    checkRuntimeDeps(runtimeDepInfo);
    JavaCompilationArgs args = JavaCompilationArgs.builder()
        .addTransitiveTargets(runtimeDepInfo, true, ClasspathType.RUNTIME_ONLY)
        .build();
    attributes.addRuntimeClassPathEntries(args.getRuntimeJars());
    attributes.addInstrumentationMetadataEntries(args.getInstrumentationMetadata());
  }

  public Iterable<SourcesJavaCompilationArgsProvider> compilationArgsFromSources() {
    return ruleContext.getPrerequisites("srcs", Mode.TARGET,
        SourcesJavaCompilationArgsProvider.class);
  }

  /**
   * Adds jars in the given group of entries to the compile time classpath after
   * using ijar to create jar interfaces for the generated jars.
   */
  private void addCompileTimeClassPathEntriesMaybeThroughIjar(
      JavaCompilationHelper helper,
      JavaTargetAttributes.Builder attributes) {
    for (FileProvider provider : ruleContext
        .getPrerequisites("srcs", Mode.TARGET, FileProvider.class)) {
      Iterable<Artifact> jarFiles = helper.filterGeneratedJarsThroughIjar(
          FileType.filter(provider.getFilesToBuild(), JavaSemantics.JAR));
      List<Artifact> jarsWithOwners = Lists.newArrayList(jarFiles);
      attributes.addDirectCompileTimeClassPathEntries(jarsWithOwners);
      attributes.addCompileTimeJarFiles(jarsWithOwners);
    }
  }

  /**
   * Adds information about the annotation processors that should be run for this java target to
   * the target attributes.
   */
  private void addPlugins(JavaTargetAttributes.Builder attributes) {
    for (JavaPluginInfoProvider plugin : activePlugins) {
      for (String name : plugin.getProcessorClasses()) {
        attributes.addProcessorName(name);
      }
      // Now get the plugin-libraries runtime classpath.
      attributes.addProcessorPath(plugin.getProcessorClasspath());
    }
  }

  private ImmutableList<JavaPluginInfoProvider> collectPlugins() {
    List<JavaPluginInfoProvider> result = new ArrayList<>();
    Iterables.addAll(result, getPluginInfoProvidersForAttribute(":java_plugins", Mode.HOST));
    Iterables.addAll(result, getPluginInfoProvidersForAttribute("plugins", Mode.HOST));
    Iterables.addAll(result, getPluginInfoProvidersForAttribute("deps", Mode.TARGET));
    return ImmutableList.copyOf(result);
  }

  Iterable<JavaPluginInfoProvider> getPluginInfoProvidersForAttribute(String attribute,
      Mode mode) {
    if (ruleContext.attributes().has(attribute, BuildType.LABEL_LIST)) {
      return ruleContext.getPrerequisites(attribute, mode, JavaPluginInfoProvider.class);
    }
    return ImmutableList.of();
  }

  /**
   * Gets all the deps.
   */
  public final Iterable<? extends TransitiveInfoCollection> getDependencies() {
    return targetsTreatedAsDeps(ClasspathType.BOTH);
  }

  /**
   * Gets all the deps that implement a particular provider.
   */
  public final <P extends TransitiveInfoProvider> Iterable<P> getDependencies(
      Class<P> provider) {
    return AnalysisUtils.getProviders(getDependencies(), provider);
  }

  /**
   * Returns true if and only if this target has the neverlink attribute set to
   * 1, or false if the neverlink attribute does not exist (for example, on
   * *_binary targets)
   *
   * @return the value of the neverlink attribute.
   */
  public static final boolean isNeverLink(RuleContext ruleContext) {
    return ruleContext.getRule().isAttrDefined("neverlink", Type.BOOLEAN) &&
        ruleContext.attributes().get("neverlink", Type.BOOLEAN);
  }

  private NestedSet<Artifact> getFilesToCompile(Artifact classJar) {
    if (classJar == null) {
      // Some subclasses don't produce jars
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
    return NestedSetBuilder.create(Order.STABLE_ORDER, classJar);
  }

  public ImmutableList<Artifact> getSrcsArtifacts() {
    return sources;
  }

  public ImmutableList<String> getJavacOpts() {
    return javacOpts;
  }

  public ImmutableList<Artifact> getBootClasspath() {
    return classpathFragment.getBootClasspath();
  }

  public NestedSet<Artifact> getRuntimeClasspath() {
    return classpathFragment.getRuntimeClasspath();
  }

  public NestedSet<Artifact> getCompileTimeClasspath() {
    return classpathFragment.getCompileTimeClasspath();
  }
}
