// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository.starlark;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.STRING;
import static com.google.devtools.build.lib.packages.Type.STRING_LIST;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtension;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionEvalStarlarkThreadContext;
import com.google.devtools.build.lib.bazel.bzlmod.TagClass;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeValueSource;
import com.google.devtools.build.lib.packages.BazelStarlarkContext;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Package.NameConflictException;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.PackageFactory.PackageContext;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.packages.RuleFunction;
import com.google.devtools.build.lib.packages.StarlarkExportable;
import com.google.devtools.build.lib.packages.WorkspaceFactoryHelper;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryModuleApi;
import java.util.Map;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

/**
 * The Starlark module containing the definition of {@code repository_rule} function to define a
 * Starlark remote repository.
 */
public class StarlarkRepositoryModule implements RepositoryModuleApi {

  @Override
  public StarlarkCallable repositoryRule(
      StarlarkCallable implementation,
      Object attrs,
      Boolean local,
      Sequence<?> environ, // <String> expected
      Boolean configure,
      Boolean remotable,
      String doc,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext context = BazelStarlarkContext.from(thread);
    context.checkLoadingOrWorkspacePhase("repository_rule");
    // We'll set the name later, pass the empty string for now.
    RuleClass.Builder builder = new RuleClass.Builder("", RuleClassType.WORKSPACE, true);

    ImmutableList<StarlarkThread.CallStackEntry> callstack = thread.getCallStack();
    builder.setCallStack(
        callstack.subList(0, callstack.size() - 1)); // pop 'repository_rule' itself

    builder.addAttribute(attr("$local", BOOLEAN).defaultValue(local).build());
    builder.addAttribute(attr("$configure", BOOLEAN).defaultValue(configure).build());
    if (thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC)) {
      builder.addAttribute(attr("$remotable", BOOLEAN).defaultValue(remotable).build());
      BaseRuleClasses.execPropertiesAttribute(builder);
    }
    builder.addAttribute(attr("$environ", STRING_LIST).defaultValue(environ).build());
    BaseRuleClasses.commonCoreAndStarlarkAttributes(builder);
    builder.add(attr("expect_failure", STRING));
    if (attrs != Starlark.NONE) {
      for (Map.Entry<String, Descriptor> attr :
          Dict.cast(attrs, String.class, Descriptor.class, "attrs").entrySet()) {
        Descriptor attrDescriptor = attr.getValue();
        AttributeValueSource source = attrDescriptor.getValueSource();
        String attrName = source.convertToNativeName(attr.getKey());
        if (builder.contains(attrName)) {
          throw Starlark.errorf(
              "There is already a built-in attribute '%s' which cannot be overridden", attrName);
        }
        builder.addAttribute(attrDescriptor.build(attrName));
      }
    }
    builder.setConfiguredTargetFunction(implementation);
    BazelModuleContext bzlModule =
        BazelModuleContext.of(Module.ofInnermostEnclosingStarlarkFunction(thread));
    builder.setRuleDefinitionEnvironmentLabelAndDigest(
        bzlModule.label(), bzlModule.bzlTransitiveDigest());
    builder.setWorkspaceOnly();
    return new RepositoryRuleFunction(builder, implementation);
  }

  // RepositoryRuleFunction is the result of repository_rule(...).
  // It is a callable value; calling it yields a Rule instance.
  @StarlarkBuiltin(
      name = "repository_rule",
      category = DocCategory.BUILTIN,
      doc =
          "A callable value that may be invoked during evaluation of the WORKSPACE file or within"
              + " the implementation function of a module extension to instantiate and return a"
              + " repository rule.")
  private static final class RepositoryRuleFunction
      implements StarlarkCallable, StarlarkExportable, RuleFunction {
    private final RuleClass.Builder builder;
    private final StarlarkCallable implementation;
    private Label extensionLabel;
    private String exportedName;

    private RepositoryRuleFunction(RuleClass.Builder builder, StarlarkCallable implementation) {
      this.builder = builder;
      this.implementation = implementation;
    }

    @Override
    public String getName() {
      return "repository_rule";
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public void export(EventHandler handler, Label extensionLabel, String exportedName) {
      this.extensionLabel = extensionLabel;
      this.exportedName = exportedName;
    }

    @Override
    public boolean isExported() {
      return extensionLabel != null;
    }

    @Override
    public void repr(Printer printer) {
      if (exportedName == null) {
        printer.append("<anonymous starlark repository rule>");
      } else {
        printer.append("<starlark repository rule " + extensionLabel + "%" + exportedName + ">");
      }
    }

    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
        throws EvalException, InterruptedException {
      if (!args.isEmpty()) {
        throw new EvalException("unexpected positional arguments");
      }
      // Decide whether we're operating in the new mode (during module extension evaluation) or in
      // legacy mode (during workspace evaluation).
      ModuleExtensionEvalStarlarkThreadContext extensionEvalContext =
          ModuleExtensionEvalStarlarkThreadContext.from(thread);
      if (extensionEvalContext == null) {
        return createRuleLegacy(thread, kwargs);
      }
      if (!isExported()) {
        throw new EvalException("attempting to instantiate a non-exported repository rule");
      }
      extensionEvalContext.createRepo(thread, kwargs, getRuleClass());
      return Starlark.NONE;
    }

    private String getRuleClassName() {
      // If the function ever got exported (the common case), we take the name
      // it was exported to. Only in the not intended case of calling an unexported
      // repository function through an exported macro, we fall back, for lack of
      // alternatives, to the name in the local context.
      // TODO(b/111199163): we probably should disallow the use of non-exported
      // repository rules anyway.
      if (isExported()) {
        return exportedName;
      } else {
        // repository_rules should be subject to the same "exported" requirement
        // as package rules, but sadly we forgot to add the necessary check and
        // now many projects create and instantiate repository_rules without an
        // intervening export; see b/111199163. An incompatible flag is required.

        // The historical workaround was a fragile hack to introspect on the call
        // expression syntax, f() or x.f(), to find the name f, but we no longer
        // have access to the call expression, so now we just create an ugly
        // name from the function. See github.com/bazelbuild/bazel/issues/10441
        return "unexported_" + implementation.getName();
      }
    }

    private Object createRuleLegacy(StarlarkThread thread, Dict<String, Object> kwargs)
        throws EvalException, InterruptedException {
      BazelStarlarkContext.from(thread).checkWorkspacePhase("repository rule " + exportedName);
      String ruleClassName = getRuleClassName();
      try {
        RuleClass ruleClass = builder.build(ruleClassName, ruleClassName);
        PackageContext context = PackageFactory.getContext(thread);
        Package.Builder packageBuilder = context.getBuilder();

        // TODO(adonovan): is this cast safe? Check.
        String name = (String) kwargs.get("name");
        if (name == null) {
          throw Starlark.errorf("argument 'name' is required");
        }
        WorkspaceFactoryHelper.addMainRepoEntry(packageBuilder, name, thread.getSemantics());
        WorkspaceFactoryHelper.addRepoMappings(packageBuilder, kwargs, name);
        Rule rule =
            WorkspaceFactoryHelper.createAndAddRepositoryRule(
                context.getBuilder(),
                ruleClass,
                /* bindRuleClass= */ null,
                WorkspaceFactoryHelper.getFinalKwargs(kwargs),
                thread.getSemantics(),
                thread.getCallStack());
        return rule;
      } catch (InvalidRuleException | NameConflictException | LabelSyntaxException e) {
        throw Starlark.errorf("%s", e.getMessage());
      }
    }

    @Override
    public RuleClass getRuleClass() {
      String name = getRuleClassName();
      return builder.build(name, name);
    }
  }

  @Override
  public void failWithIncompatibleUseCcConfigureFromRulesCc(StarlarkThread thread)
      throws EvalException {
    if (thread
        .getSemantics()
        .getBool(BuildLanguageOptions.INCOMPATIBLE_USE_CC_CONFIGURE_FROM_RULES_CC)) {
      throw Starlark.errorf(
          "Incompatible flag "
              + "--incompatible_use_cc_configure_from_rules_cc has been flipped. Please use "
              + "cc_configure and related logic from https://github.com/bazelbuild/rules_cc. "
              + "See https://github.com/bazelbuild/bazel/issues/10134 for details and migration "
              + "instructions.");
    }
  }

  @Override
  public ModuleExtension moduleExtension(
      StarlarkCallable implementation,
      Dict<?, ?> tagClasses, // Dict<String, TagClass>
      String doc,
      Sequence<?> environ, // <String>
      Boolean useOs,
      Boolean useArch,
      StarlarkThread thread)
      throws EvalException {
    return ModuleExtension.builder()
        .setImplementation(implementation)
        .setTagClasses(
            ImmutableMap.copyOf(Dict.cast(tagClasses, String.class, TagClass.class, "tag_classes")))
        .setDoc(doc)
        .setEnvVariables(ImmutableList.copyOf(Sequence.cast(environ, String.class, "environ")))
        .setLocation(thread.getCallerLocation())
        .setUseOs(useOs)
        .setUseArch(useArch)
        .build();
  }

  @Override
  public TagClass tagClass(
      Dict<?, ?> attrs, // Dict<String, StarlarkAttrModule.Descriptor>
      String doc,
      StarlarkThread thread)
      throws EvalException {
    ImmutableList.Builder<Attribute> attrBuilder = ImmutableList.builder();
    for (Map.Entry<String, Descriptor> attr :
        Dict.cast(attrs, String.class, Descriptor.class, "attrs").entrySet()) {
      Descriptor attrDescriptor = attr.getValue();
      AttributeValueSource source = attrDescriptor.getValueSource();
      String attrName = source.convertToNativeName(attr.getKey());
      attrBuilder.add(attrDescriptor.build(attrName));
      // TODO(wyv): validate attributes. No selects, no latebound defaults, or any crazy stuff like
      //   that.
    }
    return TagClass.create(attrBuilder.build(), doc, thread.getCallerLocation());
  }
}
