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

package com.google.devtools.build.lib.analysis.skylark;

import static com.google.devtools.build.lib.analysis.BaseRuleClasses.RUN_UNDER;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.LICENSE;
import static com.google.devtools.build.lib.syntax.SkylarkType.castMap;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;
import static com.google.devtools.build.lib.syntax.Type.INTEGER;
import static com.google.devtools.build.lib.syntax.Type.STRING;
import static com.google.devtools.build.lib.syntax.Type.STRING_LIST;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ActionsProvider;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.DefaultInfo;
import com.google.devtools.build.lib.analysis.TemplateVariableInfo;
import com.google.devtools.build.lib.analysis.config.ConfigAwareRuleClassBuilder;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.skylark.SkylarkAttr.Descriptor;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.LabelValidator;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.AttributeValueSource;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SkylarkImplicitOutputsFunctionWithCallback;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SkylarkImplicitOutputsFunctionWithMap;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.packages.Package.NameConflictException;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.PackageFactory.PackageContext;
import com.google.devtools.build.lib.packages.PredicateWithMessage;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleClass.ExecutionPlatformConstraintsAllowed;
import com.google.devtools.build.lib.packages.RuleFactory;
import com.google.devtools.build.lib.packages.RuleFactory.BuildLangTypedAttributeValuesMap;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.packages.RuleFunction;
import com.google.devtools.build.lib.packages.SkylarkAspect;
import com.google.devtools.build.lib.packages.SkylarkDefinedAspect;
import com.google.devtools.build.lib.packages.SkylarkExportable;
import com.google.devtools.build.lib.packages.SkylarkProvider;
import com.google.devtools.build.lib.packages.SkylarkProviderIdentifier;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skylarkbuildapi.SkylarkRuleFunctionsApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkCallbackFunction;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;
import com.google.devtools.build.lib.syntax.SkylarkType;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.syntax.Type.ConversionException;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.Pair;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * A helper class to provide an easier API for Skylark rule definitions.
 */
public class SkylarkRuleClassFunctions implements SkylarkRuleFunctionsApi<Artifact> {

  // TODO(bazel-team): Copied from ConfiguredRuleClassProvider for the transition from built-in
  // rules to skylark extensions. Using the same instance would require a large refactoring.
  // If we don't want to support old built-in rules and Skylark simultaneously
  // (except for transition phase) it's probably OK.
  private static final LoadingCache<String, Label> labelCache =
      CacheBuilder.newBuilder().build(new CacheLoader<String, Label>() {
        @Override
        public Label load(String from) throws Exception {
          try {
            return Label.parseAbsolute(from, false);
          } catch (LabelSyntaxException e) {
            throw new Exception(from);
          }
        }
      });

  // TODO(bazel-team): Remove the code duplication (BaseRuleClasses and this class).
  /** Parent rule class for non-executable non-test Skylark rules. */
  public static final RuleClass baseRule =
      BaseRuleClasses.commonCoreAndSkylarkAttributes(
              BaseRuleClasses.nameAttribute(
                      new RuleClass.Builder("$base_rule", RuleClassType.ABSTRACT, true))
                  .add(attr("expect_failure", STRING)))
          // TODO(skylark-team): Allow Skylark rules to extend native rules and remove duplication.
          .add(
              attr("toolchains", LABEL_LIST)
                  .allowedFileTypes(FileTypeSet.NO_FILE)
                  .mandatoryProviders(ImmutableList.of(TemplateVariableInfo.PROVIDER.id())))
          .build();

  /** Parent rule class for executable non-test Skylark rules. */
  public static final RuleClass binaryBaseRule =
      new RuleClass.Builder("$binary_base_rule", RuleClassType.ABSTRACT, true, baseRule)
          .add(attr("args", STRING_LIST))
          .add(attr("output_licenses", LICENSE))
          .build();

  /** Parent rule class for test Skylark rules. */
  public static final RuleClass getTestBaseRule(String toolsRepository,
      PatchTransition lipoDataTransition) {
    return new RuleClass.Builder("$test_base_rule", RuleClassType.ABSTRACT, true, baseRule)
        .requiresConfigurationFragments(TestConfiguration.class)
        .add(
            attr("size", STRING)
                .value("medium")
                .taggable()
                .nonconfigurable("used in loading phase rule validation logic"))
        .add(
            attr("timeout", STRING)
                .taggable()
                .nonconfigurable("used in loading phase rule validation logic")
                .value(timeoutAttribute))
        .add(
            attr("flaky", BOOLEAN)
                .value(false)
                .taggable()
                .nonconfigurable("taggable - called in Rule.getRuleTags"))
        .add(attr("shard_count", INTEGER).value(-1))
        .add(
            attr("local", BOOLEAN)
                .value(false)
                .taggable()
                .nonconfigurable(
                    "policy decision: this should be consistent across configurations"))
        .add(attr("args", STRING_LIST))
        // Input files for every test action
        .add(
            attr("$test_runtime", LABEL_LIST)
                .cfg(HostTransition.INSTANCE)
                .value(
                    ImmutableList.of(
                        labelCache.getUnchecked(toolsRepository + "//tools/test:runtime"))))
        .add(
            attr("$test_setup_script", LABEL)
                .cfg(HostTransition.INSTANCE)
                .singleArtifact()
                .value(labelCache.getUnchecked(toolsRepository + "//tools/test:test_setup")))
        .add(
            attr("$collect_coverage_script", LABEL)
                .cfg(HostTransition.INSTANCE)
                .singleArtifact()
                .value(labelCache.getUnchecked(toolsRepository + "//tools/test:collect_coverage")))
        // Input files for test actions collecting code coverage
        .add(
            attr(":coverage_support", LABEL)
                .cfg(HostTransition.INSTANCE)
                .value(
                    BaseRuleClasses.coverageSupportAttribute(
                        labelCache.getUnchecked(
                            toolsRepository + BaseRuleClasses.DEFAULT_COVERAGE_SUPPORT_VALUE))))
        // Used in the one-per-build coverage report generation action.
        .add(
            attr(":coverage_report_generator", LABEL)
                .cfg(HostTransition.INSTANCE)
                .value(
                    BaseRuleClasses.coverageReportGeneratorAttribute(
                        labelCache.getUnchecked(
                            toolsRepository
                                + BaseRuleClasses.DEFAULT_COVERAGE_REPORT_GENERATOR_VALUE)))
                .singleArtifact())
        .add(attr(":run_under", LABEL).cfg(lipoDataTransition).value(RUN_UNDER))
        .build();
  }

  @AutoCodec @AutoCodec.VisibleForSerialization
  static final Attribute.ComputedDefault timeoutAttribute =
      new Attribute.ComputedDefault() {
        @Override
        public Object getDefault(AttributeMap rule) {
          TestSize size = TestSize.getTestSize(rule.get("size", Type.STRING));
          if (size != null) {
            String timeout = size.getDefaultTimeout().toString();
            if (timeout != null) {
              return timeout;
            }
          }
          return "illegal";
        }
      };

  @SkylarkSignature(
    name = "DefaultInfo",
    returnType = Provider.class,
    doc =
        "A provider that gives general information about a target's direct and transitive files. "
            + "Every rule type has this provider, even if it is not returned explicitly by the "
            + "rule's implementation function."
            + "<p>The <code>DefaultInfo</code> constructor accepts the following parameters:"
            + "<ul>"
            + "<li><code>executable</code>: If this rule is marked "
            + "<a href='globals.html#rule.executable'><code>executable</code></a> or "
            + "<a href='globals.html#rule.test'><code>test</code></a>, this is a "
            + "<a href='File.html'><code>File</code></a> object representing the file that should "
            + "be executed to run the target. By default it is the predeclared output "
            + "<code>ctx.outputs.executable</code>."
            + "<li><code>files</code>: A <a href='depset.html'><code>depset</code></a> of "
            + "<a href='File.html'><code>File</code></a> objects representing the default outputs "
            + "to build when this target is specified on the blaze command line. By default it is "
            + "all predeclared outputs."
            + "<li><code>runfiles</code>: set of files acting as both the "
            + "<code>data_runfiles</code> and <code>default_runfiles</code>."
            + "<li><code>data_runfiles</code>: are the files that are added to the runfiles of a "
            + "target that depends on the rule via the <code>data</code> attribute."
            + "<li><code>default_runfiles</code>: are the files that are added to the runfiles of "
            + "a target that depends on the rule via anything but the <code>data</code> attribute."
            + "</ul>"
            + "Each <code>DefaultInfo</code> instance has the following fields: "
            + "<ul>"
            + "<li><code>files</code>"
            + "<li><code>files_to_run</code>"
            + "<li><code>data_runfiles</code>"
            + "<li><code>default_runfiles</code>"
            + "</ul>"
            + "See the <a href='../rules.$DOC_EXT'>rules</a> page for more information."
  )
  private static final NativeProvider<?> defaultInfo = DefaultInfo.PROVIDER;

  // TODO(bazel-team): Move to a "testing" namespace module. Normally we'd pass an objectType
  // to @SkylarkSignature to do this, but that doesn't work here because we're exposing an already-
  // configured BaseFunction, rather than defining a new BuiltinFunction. This should wait for
  // better support from the Skylark/Java interface, or perhaps support for first-class modules.
  @SkylarkSignature(
    name = "Actions",
    returnType = SkylarkProvider.class,
    doc =
        "<i>(Note: This is a provider type. Don't instantiate it yourself; use it to retrieve a "
            + "provider object from a <a href=\"Target.html\">Target</a>.)</i>"
            + "<br/><br/>"
            + "Provides access to the <a href=\"Action.html\">actions</a> generated by a rule. "
            + "There is one field, <code>by_file</code>, which is a dictionary from an output "
            + "of the rule to its corresponding generating action. "
            + "<br/><br/>"
            + "This is designed for testing rules, and should not be accessed outside "
            + "of test logic. This provider is only available for targets generated by rules"
            + " that have <a href=\"globals.html#rule._skylark_testable\">_skylark_testable</a> "
            + "set to <code>True</code>."
  )
  private static final NativeProvider<?> actions = ActionsProvider.SKYLARK_CONSTRUCTOR;

  @Override
  public Provider provider(String doc, Object fields, Location location) throws EvalException {
    Iterable<String> fieldNames = null;
    if (fields instanceof SkylarkList<?>) {
      @SuppressWarnings("unchecked")
      SkylarkList<String> list = (SkylarkList<String>)
              SkylarkType.cast(
                  fields,
                  SkylarkList.class, String.class, location,
                  "Expected list of strings or dictionary of string -> string for 'fields'");
      fieldNames = list;
    }  else  if (fields instanceof SkylarkDict) {
      Map<String, String> dict = SkylarkType.castMap(
          fields,
          String.class, String.class,
          "Expected list of strings or dictionary of string -> string for 'fields'");
      fieldNames = dict.keySet();
    }
    return SkylarkProvider.createUnexportedSchemaful(fieldNames, location);
  }

  // TODO(bazel-team): implement attribute copy and other rule properties
  @Override
  @SuppressWarnings({"rawtypes", "unchecked"}) // castMap produces
  // an Attribute.Builder instead of a Attribute.Builder<?> but it's OK.
  public BaseFunction rule(
      BaseFunction implementation,
      Boolean test,
      Object attrs,
      Object implicitOutputs,
      Boolean executable,
      Boolean outputToGenfiles,
      SkylarkList<?> fragments,
      SkylarkList<?> hostFragments,
      Boolean skylarkTestable,
      SkylarkList<String> toolchains,
      String doc,
      SkylarkList<?> providesArg,
      Boolean executionPlatformConstraintsAllowed,
      SkylarkList<?> execCompatibleWith,
      FuncallExpression ast,
      Environment funcallEnv)
      throws EvalException, ConversionException {
    funcallEnv.checkLoadingOrWorkspacePhase("rule", ast.getLocation());
    RuleClassType type = test ? RuleClassType.TEST : RuleClassType.NORMAL;
    RuleClass parent =
        test
            ? getTestBaseRule(
                SkylarkUtils.getToolsRepository(funcallEnv),
                (PatchTransition) SkylarkUtils.getLipoDataTransition(funcallEnv))
            : (executable ? binaryBaseRule : baseRule);

    // We'll set the name later, pass the empty string for now.
    RuleClass.Builder builder = new RuleClass.Builder("", type, true, parent);
    ImmutableList<Pair<String, SkylarkAttr.Descriptor>> attributes =
        attrObjectToAttributesList(attrs, ast);

    if (skylarkTestable) {
      builder.setSkylarkTestable();
    }

    if (executable || test) {
      addAttribute(
          ast.getLocation(),
          builder,
          attr("$is_executable", BOOLEAN)
              .value(true)
              .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
              .build());
      builder.setExecutableSkylark();
    }

    if (implicitOutputs != Runtime.NONE) {
      if (implicitOutputs instanceof BaseFunction) {
        BaseFunction func = (BaseFunction) implicitOutputs;
        SkylarkCallbackFunction callback =
            new SkylarkCallbackFunction(func, ast, funcallEnv.getSemantics());
        builder.setImplicitOutputsFunction(
            new SkylarkImplicitOutputsFunctionWithCallback(callback, ast.getLocation()));
      } else {
        builder.setImplicitOutputsFunction(
            new SkylarkImplicitOutputsFunctionWithMap(
                ImmutableMap.copyOf(
                    castMap(
                        implicitOutputs,
                        String.class,
                        String.class,
                        "implicit outputs of the rule class"))));
      }
    }

    if (outputToGenfiles) {
      builder.setOutputToGenfiles();
    }

    builder.requiresConfigurationFragmentsBySkylarkModuleName(
        fragments.getContents(String.class, "fragments"));
    ConfigAwareRuleClassBuilder.of(builder)
        .requiresHostConfigurationFragmentsBySkylarkModuleName(
            hostFragments.getContents(String.class, "host_fragments"));
    builder.setConfiguredTargetFunction(implementation);
    builder.setRuleDefinitionEnvironmentLabelAndHashCode(
        funcallEnv.getGlobals().getTransitiveLabel(),
        funcallEnv.getTransitiveContentHashCode());
    builder.addRequiredToolchains(collectToolchainLabels(toolchains, ast));

    for (Object o : providesArg) {
      if (!SkylarkAttr.isProvider(o)) {
        throw new EvalException(
            ast.getLocation(),
            String.format(
                "Illegal argument: element in 'provides' is of unexpected type. "
                    + "Should be list of providers, but got item of type %s.",
                EvalUtils.getDataTypeName(o, true)));
      }
    }
    for (SkylarkProviderIdentifier skylarkProvider :
        SkylarkAttr.getSkylarkProviderIdentifiers(providesArg, ast.getLocation())) {
      builder.advertiseSkylarkProvider(skylarkProvider);
    }

    if (!execCompatibleWith.isEmpty()) {
      builder.addExecutionPlatformConstraints(collectConstraintLabels(execCompatibleWith.getContents(String.class, "exec_compatile_with"), ast.getLocation()));
    }
    if (executionPlatformConstraintsAllowed) {
      builder.executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_TARGET);
    }

    return new SkylarkRuleFunction(builder, type, attributes, ast.getLocation());
  }

  protected static ImmutableList<Pair<String, Descriptor>> attrObjectToAttributesList(
      Object attrs, FuncallExpression ast) throws EvalException {
    ImmutableList.Builder<Pair<String, Descriptor>> attributes = ImmutableList.builder();

    if (attrs != Runtime.NONE) {
      for (Map.Entry<String, Descriptor> attr :
          castMap(attrs, String.class, Descriptor.class, "attrs").entrySet()) {
        Descriptor attrDescriptor = attr.getValue();
        AttributeValueSource source = attrDescriptor.getValueSource();
        String attrName = source.convertToNativeName(attr.getKey(), ast.getLocation());
        attributes.add(Pair.of(attrName, attrDescriptor));
      }
    }
    return attributes.build();
  }

  private static void addAttribute(
      Location location, RuleClass.Builder builder, Attribute attribute) throws EvalException {
    try {
      builder.addOrOverrideAttribute(attribute);
    } catch (IllegalArgumentException ex) {
      throw new EvalException(location, ex);
    }
  }

  private static ImmutableList<Label> collectToolchainLabels(
      Iterable<String> rawLabels, FuncallExpression ast) throws EvalException {
    ImmutableList.Builder<Label> requiredToolchains = new ImmutableList.Builder<>();
    for (String rawLabel : rawLabels) {
      try {
        Label toolchainLabel = Label.parseAbsolute(rawLabel);
        requiredToolchains.add(toolchainLabel);
      } catch (LabelSyntaxException e) {
        throw new EvalException(
            ast.getLocation(),
            String.format("Unable to parse toolchain %s: %s", rawLabel, e.getMessage()),
            e);
      }
    }

    return requiredToolchains.build();
  }

  private static ImmutableList<Label> collectConstraintLabels(
      Iterable<String> rawLabels, Location loc) throws EvalException {
    ImmutableList.Builder<Label> constraintLabels = new ImmutableList.Builder<>();
    for (String rawLabel : rawLabels) {
      try {
        Label constraintLabel = Label.parseAbsolute(rawLabel);
        constraintLabels.add(constraintLabel);
      } catch (LabelSyntaxException e) {
        throw new EvalException(
            loc,
            String.format("Unable to parse constraint %s: %s", rawLabel, e.getMessage()),
            e);
      }
    }

    return constraintLabels.build();
  }

  @Override
  public SkylarkAspect aspect(
      BaseFunction implementation,
      SkylarkList attributeAspects,
      Object attrs,
      SkylarkList requiredAspectProvidersArg,
      SkylarkList providesArg,
      SkylarkList fragments,
      SkylarkList hostFragments,
      SkylarkList<String> toolchains,
      String doc,
      FuncallExpression ast,
      Environment funcallEnv)
      throws EvalException {
    Location location = ast.getLocation();
    ImmutableList.Builder<String> attrAspects = ImmutableList.builder();
    for (Object attributeAspect : attributeAspects) {
      String attrName = STRING.convert(attributeAspect, "attr_aspects");

      if (attrName.equals("*") && attributeAspects.size() != 1) {
        throw new EvalException(
            ast.getLocation(), "'*' must be the only string in 'attr_aspects' list");
      }

      if (!attrName.startsWith("_")) {
        attrAspects.add(attrName);
      } else {
        // Implicit attribute names mean either implicit or late-bound attributes
        // (``$attr`` or ``:attr``). Depend on both.
        attrAspects.add(
            AttributeValueSource.COMPUTED_DEFAULT.convertToNativeName(attrName, location));
        attrAspects.add(
            AttributeValueSource.LATE_BOUND.convertToNativeName(attrName, location));
      }
    }

    ImmutableList<Pair<String, SkylarkAttr.Descriptor>> descriptors =
        attrObjectToAttributesList(attrs, ast);
    ImmutableList.Builder<Attribute> attributes = ImmutableList.builder();
    ImmutableSet.Builder<String> requiredParams = ImmutableSet.builder();
    for (Pair<String, Descriptor> nameDescriptorPair : descriptors) {
      String nativeName = nameDescriptorPair.first;
      boolean hasDefault = nameDescriptorPair.second.hasDefault();
      Attribute attribute = nameDescriptorPair.second.build(nameDescriptorPair.first);
      if (attribute.getType() == Type.STRING
          && ((String) attribute.getDefaultValue(null)).isEmpty()) {
        hasDefault = false; // isValueSet() is always true for attr.string.
      }
      if (!Attribute.isImplicit(nativeName) && !Attribute.isLateBound(nativeName)) {
        if (!attribute.checkAllowedValues() || attribute.getType() != Type.STRING) {
          throw new EvalException(
              ast.getLocation(),
              String.format(
                  "Aspect parameter attribute '%s' must have type 'string' and use the "
                      + "'values' restriction.",
                  nativeName));
        }
        if (!hasDefault) {
          requiredParams.add(nativeName);
        } else {
          PredicateWithMessage<Object> allowed = attribute.getAllowedValues();
          Object defaultVal = attribute.getDefaultValue(null);
          if (!allowed.apply(defaultVal)) {
            throw new EvalException(
                ast.getLocation(),
                String.format(
                    "Aspect parameter attribute '%s' has a bad default value: %s",
                    nativeName, allowed.getErrorReason(defaultVal)));
          }
        }
      } else if (!hasDefault) { // Implicit or late bound attribute
        String skylarkName = "_" + nativeName.substring(1);
        throw new EvalException(
            ast.getLocation(),
            String.format("Aspect attribute '%s' has no default value.", skylarkName));
      }
      attributes.add(attribute);
    }

    for (Object o : providesArg) {
      if (!SkylarkAttr.isProvider(o)) {
        throw new EvalException(
            ast.getLocation(),
            String.format(
                "Illegal argument: element in 'provides' is of unexpected type. "
                    + "Should be list of providers, but got item of type %s. ",
                EvalUtils.getDataTypeName(o, true)));
      }
    }
    return new SkylarkDefinedAspect(
        implementation,
        attrAspects.build(),
        attributes.build(),
        SkylarkAttr.buildProviderPredicate(
            requiredAspectProvidersArg, "required_aspect_providers", ast.getLocation()),
        SkylarkAttr.getSkylarkProviderIdentifiers(providesArg, ast.getLocation()),
        requiredParams.build(),
        ImmutableSet.copyOf(fragments.getContents(String.class, "fragments")),
        HostTransition.INSTANCE,
        ImmutableSet.copyOf(hostFragments.getContents(String.class, "host_fragments")),
        collectToolchainLabels(toolchains, ast));
  }

  /**
   * The implementation for the magic function "rule" that creates Skylark rule classes.
   *
   * <p>Exactly one of {@link #builder} or {@link #ruleClass} is null except inside {@link #export}.
   */
  public static final class SkylarkRuleFunction extends BaseFunction
      implements SkylarkExportable, RuleFunction {
    private RuleClass.Builder builder;

    private RuleClass ruleClass;
    private final RuleClassType type;
    private ImmutableList<Pair<String, SkylarkAttr.Descriptor>> attributes;
    private final Location definitionLocation;
    private Label skylarkLabel;

    public SkylarkRuleFunction(
        RuleClass.Builder builder,
        RuleClassType type,
        ImmutableList<Pair<String, SkylarkAttr.Descriptor>> attributes,
        Location definitionLocation) {
      super("rule", FunctionSignature.KWARGS);
      this.builder = builder;
      this.type = type;
      this.attributes = attributes;
      this.definitionLocation = definitionLocation;
    }

    /** This is for post-export reconstruction for serialization. */
    private SkylarkRuleFunction(
        RuleClass ruleClass, RuleClassType type, Location definitionLocation, Label skylarkLabel) {
      super("rule", FunctionSignature.KWARGS);
      Preconditions.checkNotNull(
          ruleClass,
          "RuleClass must be non-null as this SkylarkRuleFunction should have been exported.");
      Preconditions.checkNotNull(
          skylarkLabel,
          "Label must be non-null as this SkylarkRuleFunction should have been exported.");
      this.ruleClass = ruleClass;
      this.type = type;
      this.definitionLocation = definitionLocation;
      this.skylarkLabel = skylarkLabel;
    }

    @Override
    @SuppressWarnings("unchecked") // the magic hidden $pkg_context variable is guaranteed
    // to be a PackageContext
    public Object call(Object[] args, FuncallExpression ast, Environment env)
        throws EvalException, InterruptedException, ConversionException {
      env.checkLoadingPhase(getName(), ast.getLocation());
      if (ruleClass == null) {
        throw new EvalException(ast.getLocation(),
            "Invalid rule class hasn't been exported by a Skylark file");
      }

      for (Attribute attribute : ruleClass.getAttributes()) {
        // TODO(dslomov): If a Skylark parameter extractor is specified for this aspect, its
        // attributes may not be required.
        for (Map.Entry<String, ImmutableSet<String>> attrRequirements :
            attribute.getRequiredAspectParameters().entrySet()) {
          for (String required : attrRequirements.getValue()) {
            if (!ruleClass.hasAttr(required, Type.STRING)) {
              throw new EvalException(definitionLocation, String.format(
                  "Aspect %s requires rule %s to specify attribute '%s' with type string.",
                  attrRequirements.getKey(),
                  ruleClass.getName(),
                  required));
            }
          }
        }
      }

      BuildLangTypedAttributeValuesMap attributeValues =
          new BuildLangTypedAttributeValuesMap((Map<String, Object>) args[0]);
      try {
        PackageContext pkgContext = (PackageContext) env.lookup(PackageFactory.PKG_CONTEXT);
        if (pkgContext == null) {
          throw new EvalException(ast.getLocation(),
              "Cannot instantiate a rule when loading a .bzl file. Rules can only be called from "
                  + "a BUILD file (possibly via a macro).");
        }
        RuleFactory.createAndAddRule(
            pkgContext,
            ruleClass,
            attributeValues,
            ast,
            env,
            pkgContext.getAttributeContainerFactory().apply(ruleClass));
        return Runtime.NONE;
      } catch (InvalidRuleException | NameConflictException e) {
        throw new EvalException(ast.getLocation(), e.getMessage());
      }
    }

    /**
     * Export a RuleFunction from a Skylark file with a given name.
     */
    public void export(Label skylarkLabel, String ruleClassName) throws EvalException {
      Preconditions.checkState(ruleClass == null && builder != null);
      this.skylarkLabel = skylarkLabel;
      if (type == RuleClassType.TEST != TargetUtils.isTestRuleName(ruleClassName)) {
        throw new EvalException(definitionLocation, "Invalid rule class name '" + ruleClassName
            + "', test rule class names must end with '_test' and other rule classes must not");
      }
      for (Pair<String, SkylarkAttr.Descriptor> attribute : attributes) {
        SkylarkAttr.Descriptor descriptor = attribute.getSecond();

        addAttribute(definitionLocation, builder,
            descriptor.build(attribute.getFirst()));
      }
      try {
        this.ruleClass = builder.build(ruleClassName, skylarkLabel + "%" + ruleClassName);
      } catch (IllegalArgumentException | IllegalStateException ex) {
        // RuleClass.Builder.build throws these to signal invalid states during rule class generation.
        throw new EvalException(location, ex);
      }

      this.builder = null;
      this.attributes = null;
    }

    public RuleClass getRuleClass() {
      Preconditions.checkState(ruleClass != null && builder == null);
      return ruleClass;
    }

    @Override
    public boolean isExported() {
      return skylarkLabel != null;
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<rule>");
    }
  }

  /**
   * All classes of values that need special processing after they are exported from an extension
   * file.
   *
   * <p>Order in list is significant: all {@link SkylarkDefinedAspect}s need to be exported before
   * {@link SkylarkRuleFunction}s etc.
   */
  private static final ImmutableList<Class<? extends SkylarkExportable>> EXPORTABLES =
      ImmutableList.of(
          SkylarkProvider.class, SkylarkDefinedAspect.class, SkylarkRuleFunction.class);

  @Override
  public Label label(
      String labelString, Boolean relativeToCallerRepository, Location loc, Environment env)
      throws EvalException {
    Label parentLabel = null;
    if (relativeToCallerRepository) {
      parentLabel = env.getCallerLabel();
    } else {
      parentLabel = env.getGlobals().getTransitiveLabel();
    }
    try {
      if (parentLabel != null) {
        LabelValidator.parseAbsoluteLabel(labelString);
        labelString = parentLabel.getRelative(labelString).getUnambiguousCanonicalForm();
      }
      return labelCache.get(labelString);
    } catch (LabelValidator.BadLabelException | LabelSyntaxException | ExecutionException e) {
      throw new EvalException(loc, "Illegal absolute label syntax: " + labelString);
    }
  }

  @Override
  public SkylarkFileType fileType(SkylarkList types, Location loc, Environment env)
      throws EvalException {
    if (env.getSemantics().incompatibleDisallowFileType()) {
      throw new EvalException(
          loc,
          "FileType function is not available. You may use a list of strings instead. "
              + "You can temporarily reenable the function by passing the flag "
              + "--incompatible_disallow_filetype=false");
    }
    return SkylarkFileType.of(types.getContents(String.class, "types"));
  }

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(SkylarkRuleClassFunctions.class);
  }
}
