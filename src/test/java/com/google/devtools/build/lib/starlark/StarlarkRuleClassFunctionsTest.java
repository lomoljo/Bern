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

package com.google.devtools.build.lib.starlark;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.analysis.testing.ExecGroupSubject.assertThat;
import static com.google.devtools.build.lib.analysis.testing.RuleClassSubject.assertThat;
import static com.google.devtools.build.lib.analysis.testing.StarlarkDefinedAspectSubject.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleClassFunctions.StarlarkRuleFunction;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.TestAspects;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.packages.AdvertisedProviderSet;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.PredicateWithMessage;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RequiredProviders;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.StarlarkAspectClass;
import com.google.devtools.build.lib.packages.StarlarkDefinedAspect;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.starlark.util.BazelEvaluationTestCase;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.Structure;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for StarlarkRuleClassFunctions. */
@RunWith(JUnit4.class)
public final class StarlarkRuleClassFunctionsTest extends BuildViewTestCase {

  private final BazelEvaluationTestCase ev = new BazelEvaluationTestCase();

  private StarlarkRuleContext createRuleContext(String label) throws Exception {
    return new StarlarkRuleContext(getRuleContextForStarlark(getConfiguredTarget(label)), null);
  }

  @Override
  protected void setBuildLanguageOptions(String... options) throws Exception {
    super.setBuildLanguageOptions(options); // for BuildViewTestCase
    ev.setSemantics(options); // for StarlarkThread
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addStarlarkAccessibleTopLevels(
        "parametrized_native_aspect",
        TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER);
    builder.addNativeAspectClass(TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER);
    return builder.build();
  }

  @org.junit.Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public final void createBuildFile() throws Exception {
    scratch.file(
        "foo/BUILD",
        "genrule(name = 'foo',",
        "  cmd = 'dummy_cmd',",
        "  srcs = ['a.txt', 'b.img'],",
        "  tools = ['t.exe'],",
        "  outs = ['c.txt'])",
        "genrule(name = 'bar',",
        "  cmd = 'dummy_cmd',",
        "  srcs = [':jl', ':gl'],",
        "  outs = ['d.txt'])",
        "java_library(name = 'jl',",
        "  srcs = ['a.java'])",
        "genrule(name = 'gl',",
        "  cmd = 'touch $(OUTS)',",
        "  srcs = ['a.go'],",
        "  outs = [ 'gl.a', 'gl.gcgox', ],",
        "  output_to_bindir = 1,",
        ")");
  }

  @Test
  public void testCannotOverrideBuiltInAttribute() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def impl(ctx):", //
        "  return",
        "r = rule(impl, attrs = {'tags': attr.string_list()})");
    ev.assertContainsError(
        "There is already a built-in attribute 'tags' which cannot be overridden");
  }

  @Test
  public void testCannotOverrideBuiltInAttributeName() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def impl(ctx):", //
        "  return",
        "r = rule(impl, attrs = {'name': attr.string()})");
    ev.assertContainsError(
        "There is already a built-in attribute 'name' which cannot be overridden");
  }

  @Test
  public void testImplicitArgsAttribute() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def _impl(ctx):",
        "  pass",
        "exec_rule = rule(implementation = _impl, executable = True)",
        "non_exec_rule = rule(implementation = _impl)");
    assertThat(getRuleClass("exec_rule").hasAttr("args", Type.STRING_LIST)).isTrue();
    assertThat(getRuleClass("non_exec_rule").hasAttr("args", Type.STRING_LIST)).isFalse();
  }

  private RuleClass getRuleClass(String name) throws Exception {
    return ((StarlarkRuleFunction) ev.lookup(name)).getRuleClass();
  }

  private void registerDummyStarlarkFunction() throws Exception {
    ev.exec("def impl():", "  pass");
  }

  @Test
  public void testAttrWithOnlyType() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list()");
    assertThat(attr.getType()).isEqualTo(Type.STRING_LIST);
  }

  private Attribute buildAttribute(String name, String... lines) throws Exception {
    String[] strings = lines.clone();
    strings[strings.length - 1] = String.format("%s = %s", name, strings[strings.length - 1]);
    evalAndExport(ev, strings);
    StarlarkAttrModule.Descriptor lookup = (StarlarkAttrModule.Descriptor) ev.lookup(name);
    return lookup != null ? lookup.build(name) : null;
  }

  @Test
  public void testOutputListAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.output_list()");
    assertThat(attr.getType()).isEqualTo(BuildType.OUTPUT_LIST);
  }

  @Test
  public void testIntListAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.int_list()");
    assertThat(attr.getType()).isEqualTo(Type.INTEGER_LIST);
  }

  @Test
  public void testOutputAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.output()");
    assertThat(attr.getType()).isEqualTo(BuildType.OUTPUT);
  }

  @Test
  public void testStringDictAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_dict(default = {'a': 'b'})");
    assertThat(attr.getType()).isEqualTo(Type.STRING_DICT);
  }

  @Test
  public void testStringListDictAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list_dict(default = {'a': ['b', 'c']})");
    assertThat(attr.getType()).isEqualTo(Type.STRING_LIST_DICT);
  }

  @Test
  public void testAttrAllowedFileTypesAnyFile() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label_list(allow_files = True)");
    assertThat(attr.getAllowedFileTypesPredicate()).isEqualTo(FileTypeSet.ANY_FILE);
  }

  @Test
  public void testAttrAllowedFileTypesWrongType() throws Exception {
    ev.checkEvalErrorContains(
        "got value of type 'int', want 'bool, sequence, or NoneType'",
        "attr.label_list(allow_files = 18)");
  }

  @Test
  public void testAttrNameSpecialCharactersAreForbidden() throws Exception {
    ev.setFailFast(false);
    evalAndExport(ev, "def impl(ctx): return", "r = rule(impl, attrs = {'ab$c': attr.int()})");
    ev.assertContainsError("attribute name `ab$c` is not a valid identifier");
  }

  @Test
  public void testAttrNameCannotStartWithDigit() throws Exception {
    ev.setFailFast(false);
    evalAndExport(ev, "def impl(ctx): return", "r = rule(impl, attrs = {'2_foo': attr.int()})");
    ev.assertContainsError("attribute name `2_foo` is not a valid identifier");
  }

  @Test
  public void testRuleClassTooManyAttributes() throws Exception {
    ev.setFailFast(false);

    ImmutableList.Builder<String> linesBuilder =
        ImmutableList.<String>builder()
            .add("def impl(ctx): return")
            .add("r = rule(impl, attrs = {");
    for (int i = 0; i < 200; i++) {
      linesBuilder.add("    'attr" + i + "': attr.int(),");
    }
    linesBuilder.add("})");

    evalAndExport(ev, linesBuilder.build().toArray(new String[0]));

    assertThat(ev.getEventCollector()).hasSize(1);
    Event event = ev.getEventCollector().iterator().next();
    assertThat(event.getKind()).isEqualTo(EventKind.ERROR);
    assertThat(event.getMessage()).contains("Rule class r declared too many attributes");
  }

  @Test
  public void testRuleClassTooLongAttributeName() throws Exception {
    ev.setFailFast(false);

    evalAndExport(
        ev,
        "def impl(ctx): return;",
        "r = rule(impl, attrs = { '" + "x".repeat(150) + "': attr.int() })");

    assertThat(ev.getEventCollector()).hasSize(1);
    Event event = ev.getEventCollector().iterator().next();
    assertThat(event.getKind()).isEqualTo(EventKind.ERROR);
    assertThat(event.getLocation().toString()).isEqualTo(":2:9");
    assertThat(event.getMessage())
        .matches("Attribute r\\.x{150}'s name is too long \\(150 > 128\\)");
  }

  @Test
  public void testAttrAllowedSingleFileTypesWrongType() throws Exception {
    ev.checkEvalErrorContains(
        "allow_single_file should be a boolean or a string list",
        "attr.label(allow_single_file = 18)");
  }

  @Test
  public void testAttrWithList() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label_list(allow_files = ['.xml'])");
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.xml")).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.txt")).isFalse();
    assertThat(attr.isSingleArtifact()).isFalse();
  }

  @Test
  public void testAttrSingleFileWithList() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(allow_single_file = ['.xml'])");
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.xml")).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.txt")).isFalse();
    assertThat(attr.isSingleArtifact()).isTrue();
  }

  private static StarlarkProviderIdentifier legacy(String legacyId) {
    return StarlarkProviderIdentifier.forLegacy(legacyId);
  }

  private static StarlarkProviderIdentifier declared(String exportedName) {
    return StarlarkProviderIdentifier.forKey(new StarlarkProvider.Key(FAKE_LABEL, exportedName));
  }

  @Test
  public void testAttrWithProviders() throws Exception {
    Attribute attr =
        buildAttribute("a1",
            "b = provider()",
            "attr.label_list(allow_files = True, providers = ['a', b])");
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a"), declared("b")))).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a")))).isFalse();
  }

  @Test
  public void testAttrWithProvidersOneEmpty() throws Exception {
    Attribute attr =
        buildAttribute(
            "a1",
            "b = provider()",
            "attr.label_list(allow_files = True, providers = [['a', b],[]])");
    assertThat(attr.getRequiredProviders().acceptsAny()).isTrue();
  }

  @Test
  public void testAttrWithProvidersList() throws Exception {
    Attribute attr =
        buildAttribute("a1",
            "b = provider()",
            "attr.label_list(allow_files = True, providers = [['a', b], ['c']])");
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a"), declared("b")))).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("c")))).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a")))).isFalse();
  }

  private static AdvertisedProviderSet set(StarlarkProviderIdentifier... ids) {
    AdvertisedProviderSet.Builder builder = AdvertisedProviderSet.builder();
    for (StarlarkProviderIdentifier id : ids) {
      builder.addStarlark(id);
    }
    return builder.build();
  }

  private void checkAttributeError(String expectedMessage, String... lines) throws Exception {
    ev.setFailFast(false);
    buildAttribute("fakeAttribute", lines);
    MoreAsserts.assertContainsEvent(ev.getEventCollector(), expectedMessage);
  }

  @Test
  public void testAttrWithWrongProvidersList() throws Exception {
    checkAttributeError(
        "element in 'providers' is of unexpected type. Either all elements should be providers,"
            + " or all elements should be lists of providers,"
            + " but got list with an element of type int.",
        "attr.label_list(allow_files = True,  providers = [['a', 1], ['c']])");

    checkAttributeError(
        "element in 'providers' is of unexpected type. Either all elements should be providers,"
            + " or all elements should be lists of providers,"
            + " but got an element of type string.",
        "b = provider()",
        "attr.label_list(allow_files = True,  providers = [['a', b], 'c'])");

    checkAttributeError(
        "element in 'providers' is of unexpected type. Either all elements should be providers,"
            + " or all elements should be lists of providers,"
            + " but got an element of type string.",
        "c = provider()",
        "attr.label_list(allow_files = True,  providers = [['a', b], c])");
  }

  @Test
  public void testLabelListWithAspects() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(implementation = _impl)",
        "a = attr.label_list(aspects = [my_aspect])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(aspect).isNotNull();
    assertThat(attr.build("xxx").getAspectClasses()).containsExactly(aspect.getAspectClass());
  }

  @Test
  public void testLabelWithAspects() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(implementation = _impl)",
        "a = attr.label(aspects = [my_aspect])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(aspect).isNotNull();
    assertThat(attr.build("xxx").getAspectClasses()).containsExactly(aspect.getAspectClass());
  }

  @Test
  public void testLabelListWithAspectsError() throws Exception {
    ev.checkEvalErrorContains(
        "at index 0 of aspects, got element of type int, want Aspect",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(implementation = _impl)",
        "attr.label_list(aspects = [my_aspect, 123])");
  }

  @Test
  public void testAttrWithAspectRequiringAspects_stackOfRequiredAspects() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_c = aspect(implementation = _impl)",
        "aspect_b = aspect(implementation = _impl, requires = [aspect_c])",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_b])",
        "a = attr.label_list(aspects = [aspect_a])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");

    StarlarkDefinedAspect aspectA = (StarlarkDefinedAspect) ev.lookup("aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkDefinedAspect aspectB = (StarlarkDefinedAspect) ev.lookup("aspect_b");
    assertThat(aspectB).isNotNull();
    StarlarkDefinedAspect aspectC = (StarlarkDefinedAspect) ev.lookup("aspect_c");
    assertThat(aspectC).isNotNull();
    List<AspectClass> expectedAspects =
        Arrays.asList(aspectA.getAspectClass(), aspectB.getAspectClass(), aspectC.getAspectClass());
    assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects);
  }

  @Test
  public void testAttrWithAspectRequiringAspects_aspectRequiredByMultipleAspects()
      throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_c = aspect(implementation = _impl)",
        "aspect_b = aspect(implementation = _impl, requires = [aspect_c])",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_c])",
        "a = attr.label_list(aspects = [aspect_a, aspect_b])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");

    StarlarkDefinedAspect aspectA = (StarlarkDefinedAspect) ev.lookup("aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkDefinedAspect aspectB = (StarlarkDefinedAspect) ev.lookup("aspect_b");
    assertThat(aspectB).isNotNull();
    StarlarkDefinedAspect aspectC = (StarlarkDefinedAspect) ev.lookup("aspect_c");
    assertThat(aspectC).isNotNull();
    List<AspectClass> expectedAspects =
        Arrays.asList(aspectA.getAspectClass(), aspectB.getAspectClass(), aspectC.getAspectClass());
    assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects);
  }

  @Test
  public void testAttrWithAspectRequiringAspects_aspectRequiredByMultipleAspects2()
      throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_d = aspect(implementation = _impl)",
        "aspect_c = aspect(implementation = _impl, requires = [aspect_d])",
        "aspect_b = aspect(implementation = _impl, requires = [aspect_d])",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_b, aspect_c])",
        "a = attr.label_list(aspects = [aspect_a])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");

    StarlarkDefinedAspect aspectA = (StarlarkDefinedAspect) ev.lookup("aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkDefinedAspect aspectB = (StarlarkDefinedAspect) ev.lookup("aspect_b");
    assertThat(aspectB).isNotNull();
    StarlarkDefinedAspect aspectC = (StarlarkDefinedAspect) ev.lookup("aspect_c");
    assertThat(aspectC).isNotNull();
    StarlarkDefinedAspect aspectD = (StarlarkDefinedAspect) ev.lookup("aspect_d");
    assertThat(aspectD).isNotNull();
    List<AspectClass> expectedAspects =
        Arrays.asList(
            aspectA.getAspectClass(),
            aspectB.getAspectClass(),
            aspectC.getAspectClass(),
            aspectD.getAspectClass());
    assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects);
  }

  @Test
  public void testAttrWithAspectRequiringAspects_requireExistingAspect_passed() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_b = aspect(implementation = _impl)",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_b])",
        "a = attr.label_list(aspects = [aspect_b, aspect_a])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");

    StarlarkDefinedAspect aspectA = (StarlarkDefinedAspect) ev.lookup("aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkDefinedAspect aspectB = (StarlarkDefinedAspect) ev.lookup("aspect_b");
    assertThat(aspectB).isNotNull();
    List<AspectClass> expectedAspects =
        Arrays.asList(aspectA.getAspectClass(), aspectB.getAspectClass());
    assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects);
  }

  @Test
  public void testAttrWithAspectRequiringAspects_requireExistingAspect_failed() throws Exception {
    ev.setFailFast(false);

    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_b = aspect(implementation = _impl)",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_b])",
        "attr.label_list(aspects = [aspect_a, aspect_b])");

    ev.assertContainsError(
        String.format(
            "aspect %s%%aspect_b was added before as a required aspect of aspect %s%%aspect_a",
            FAKE_LABEL, FAKE_LABEL));
  }

  @Test
  public void testAspectExtraDeps() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { '_extra_deps' : attr.label(default = Label('//foo/bar:baz')) }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("$extra_deps");
    assertThat(attribute.getDefaultValue(null))
        .isEqualTo(Label.parseAbsoluteUnchecked("//foo/bar:baz"));
  }

  @Test
  public void testAspectParameter() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(values=['a', 'b']) }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("param");
  }

  @Test
  public void testAspectParameterWithDefaultValue() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(default = 'a', values=['a', 'b']) }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("param");
    assertThat(((String) attribute.getDefaultValueUnchecked())).isEqualTo("a");
  }

  @Test
  public void testAspectParameterBadDefaultValue() throws Exception {
    ev.checkEvalErrorContains(
        "Aspect parameter attribute 'param' has a bad default value: has to be"
            + " one of 'b' instead of 'a'",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(default = 'a', values = ['b']) }",
        ")");
  }

  @Test
  public void testAspectParameterNotRequireValues() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(default = 'val') }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("param");
    assertThat(((String) attribute.getDefaultValueUnchecked())).isEqualTo("val");
  }

  @Test
  public void testAspectParameterBadType() throws Exception {
    ev.checkEvalErrorContains(
        "Aspect parameter attribute 'param' must have type 'bool', 'int' or 'string'.",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.label(default = Label('//foo/bar:baz')) }",
        ")");
  }

  @Test
  public void testAspectParameterAndExtraDeps() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(values=['a', 'b']),",
        "             '_extra' : attr.label(default = Label('//foo/bar:baz')) }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(aspect.getAttributes()).hasSize(2);
    assertThat(aspect.getParamAttributes()).containsExactly("param");
  }

  @Test
  public void testAspectNoDefaultValueAttribute() throws Exception {
    ev.checkEvalErrorContains(
        "Aspect attribute '_extra_deps' has no default value",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { '_extra_deps' : attr.label() }",
        ")");
  }

  @Test
  public void testAspectAddToolchain() throws Exception {
    evalAndExport(
        ev,
        "def _impl(ctx): pass",
        "a1 = aspect(_impl,",
        "    toolchains=[",
        "        '//test:my_toolchain_type1',",
        "        config_common.toolchain_type('//test:my_toolchain_type2'),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
        "        config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
        "    ],",
        ")");
    StarlarkDefinedAspect a = (StarlarkDefinedAspect) ev.lookup("a1");
    assertThat(a).hasToolchainType("//test:my_toolchain_type1");
    assertThat(a).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(a).hasToolchainType("//test:my_toolchain_type2");
    assertThat(a).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(a).hasToolchainType("//test:my_toolchain_type3");
    assertThat(a).toolchainType("//test:my_toolchain_type3").isOptional();
    assertThat(a).hasToolchainType("//test:my_toolchain_type4");
    assertThat(a).toolchainType("//test:my_toolchain_type4").isMandatory();
  }

  @Test
  public void testNonLabelAttrWithProviders() throws Exception {
    ev.checkEvalErrorContains(
        "unexpected keyword argument 'providers'", "attr.string(providers = ['a'])");
  }

  private static final RuleClass.ConfiguredTargetFactory<Object, Object, Exception>
      DUMMY_CONFIGURED_TARGET_FACTORY =
          ruleContext -> {
            throw new IllegalStateException();
          };

  private RuleClass ruleClass(String name) {
    return new RuleClass.Builder(name, RuleClassType.NORMAL, false)
        .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
        .add(Attribute.attr("tags", Type.STRING_LIST))
        .build();
  }

  @Test
  public void testAttrAllowedRuleClassesSpecificRuleClasses() throws Exception {
    Attribute attr = buildAttribute("a",
        "attr.label_list(allow_rules = ['java_binary'], allow_files = True)");
    assertThat(attr.getAllowedRuleClassesPredicate().apply(ruleClass("java_binary"))).isTrue();
    assertThat(attr.getAllowedRuleClassesPredicate().apply(ruleClass("genrule"))).isFalse();
  }

  @Test
  public void testAttrDefaultValue() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string(default = 'some value')");
    assertThat(attr.getDefaultValueUnchecked()).isEqualTo("some value");
  }

  @Test
  public void testLabelAttrDefaultValueAsString() throws Exception {
    Attribute sligleAttr = buildAttribute("a1", "attr.label(default = '//foo:bar')");
    assertThat(sligleAttr.getDefaultValueUnchecked())
        .isEqualTo(Label.parseAbsoluteUnchecked("//foo:bar"));

    Attribute listAttr =
        buildAttribute("a2", "attr.label_list(default = ['//foo:bar', '//bar:foo'])");
    assertThat(listAttr.getDefaultValueUnchecked())
        .isEqualTo(
            ImmutableList.of(
                Label.parseAbsoluteUnchecked("//foo:bar"),
                Label.parseAbsoluteUnchecked("//bar:foo")));

    Attribute dictAttr =
        buildAttribute("a3", "attr.label_keyed_string_dict(default = {'//foo:bar': 'my value'})");
    assertThat(dictAttr.getDefaultValueUnchecked())
        .isEqualTo(ImmutableMap.of(Label.parseAbsoluteUnchecked("//foo:bar"), "my value"));
  }

  @Test
  public void testLabelAttrDefaultValueAsStringBadValue() throws Exception {
    ev.checkEvalErrorContains(
        "invalid label '/foo:bar' in parameter 'default' of attribute 'label': "
            + "invalid package name '/foo': package names may not start with '/'",
        "attr.label(default = '/foo:bar')");

    ev.checkEvalErrorContains(
        "invalid label '/bar:foo' in element 1 of parameter 'default' of attribute "
            + "'label_list': invalid package name '/bar': package names may not start with '/'",
        "attr.label_list(default = ['//foo:bar', '/bar:foo'])");

    ev.checkEvalErrorContains(
        "invalid label '/bar:foo' in dict key element: invalid package name '/bar': "
            + "package names may not start with '/'",
        "attr.label_keyed_string_dict(default = {'//foo:bar': 'a', '/bar:foo': 'b'})");
  }

  @Test
  public void testAttrDefaultValueBadType() throws Exception {
    ev.checkEvalErrorContains("got value of type 'int', want 'string'", "attr.string(default = 1)");
  }

  @Test
  public void testAttrMandatory() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string(mandatory=True)");
    assertThat(attr.isMandatory()).isTrue();
    assertThat(attr.isNonEmpty()).isFalse();
  }

  @Test
  public void testAttrAllowEmpty() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list(allow_empty=False)");
    assertThat(attr.isNonEmpty()).isTrue();
    assertThat(attr.isMandatory()).isFalse();
  }

  @Test
  public void testAttrBadKeywordArguments() throws Exception {
    ev.checkEvalErrorContains(
        "string() got unexpected keyword argument 'bad_keyword'", "attr.string(bad_keyword = '')");
  }

  @Test
  public void testAttrCfgNoMoreHost() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(cfg = 'host', allow_files = True)");
    assertThat(attr.getTransitionFactory().isHost()).isFalse();
    assertThat(attr.getTransitionFactory().isTool()).isTrue();
  }

  @Test
  public void testAttrCfgTarget() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(cfg = 'target', allow_files = True)");
    assertThat(NoTransition.isInstance(attr.getTransitionFactory())).isTrue();
  }

  @Test
  public void incompatibleDataTransition() throws Exception {
    EvalException expected =
        assertThrows(EvalException.class, () -> ev.eval("attr.label(cfg = 'data')"));
    assertThat(expected).hasMessageThat().contains("cfg must be either 'host', 'target'");
  }

  @Test
  public void testAttrValues() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string(values = ['ab', 'cd'])");
    PredicateWithMessage<Object> predicate = attr.getAllowedValues();
    assertThat(predicate.apply("ab")).isTrue();
    assertThat(predicate.apply("xy")).isFalse();
  }

  @Test
  public void testAttrIntValues() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.int(values = [1, 2])");
    PredicateWithMessage<Object> predicate = attr.getAllowedValues();
    assertThat(predicate.apply(StarlarkInt.of(2))).isTrue();
    assertThat(predicate.apply(StarlarkInt.of(3))).isFalse();
  }

  @Test
  public void testAttrDoc() throws Exception {
    // We don't actually store the doc in the attr definition; right now it's just meant to be
    // extracted by documentation generating tools. So we don't have anything to assert and we just
    // verify that no exceptions were thrown from building them.
    buildAttribute("a1", "attr.bool(doc='foo')");
    buildAttribute("a2", "attr.int(doc='foo')");
    buildAttribute("a3", "attr.int_list(doc='foo')");
    buildAttribute("a4", "attr.label(doc='foo')");
    buildAttribute("a5", "attr.label_keyed_string_dict(doc='foo')");
    buildAttribute("a6", "attr.label_list(doc='foo')");
    buildAttribute("a8", "attr.output(doc='foo')");
    buildAttribute("a9", "attr.output_list(doc='foo')");
    buildAttribute("a10", "attr.string(doc='foo')");
    buildAttribute("a11", "attr.string_dict(doc='foo')");
    buildAttribute("a12", "attr.string_list(doc='foo')");
    buildAttribute("a13", "attr.string_list_dict(doc='foo')");
  }

  @Test
  public void testNoAttrLicense() throws Exception {
    EvalException expected = assertThrows(EvalException.class, () -> ev.eval("attr.license()"));
    assertThat(expected).hasMessageThat().contains("'attr' value has no field or method 'license'");
  }

  @Test
  public void testAttrDocValueBadType() throws Exception {
    ev.checkEvalErrorContains("got value of type 'int', want 'string'", "attr.string(doc = 1)");
  }

  @Test
  public void testRuleImplementation() throws Exception {
    evalAndExport(ev, "def impl(ctx): return None", "rule1 = rule(impl)");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("rule1")).getRuleClass();
    assertThat(c.getConfiguredTargetFunction().getName()).isEqualTo("impl");
  }

  @Test
  public void testRuleDoc() throws Exception {
    evalAndExport(ev, "def impl(ctx): return None", "rule1 = rule(impl, doc='foo')");
  }

  @Test
  public void testFunctionAsAttrDefault() throws Exception {
    ev.exec("def f(): pass");

    // Late-bound attributes, which are computed during analysis as a function
    // of the configuration, are only available for attributes involving labels:
    //   attr.label
    //   attr.label_list
    //   attr.label_keyed_string_dict
    //   attr.output,
    //   attr.output_list
    // (See testRuleClassImplicitOutputFunctionDependingOnComputedAttribute
    // for a more detailed positive test.)
    evalAndExport(
        ev,
        "attr.label(default=f)",
        "attr.label_list(default=f)",
        "attr.label_keyed_string_dict(default=f)");
    // For all other attribute types, the default value may not be a function.
    //
    // (This is a regression test for github.com/bazelbuild/bazel/issues/9463.
    // The loading-phase feature of "computed attribute defaults" is not exposed
    // to Starlark; the bug was that the @StarlarkMethod
    // annotation was more permissive than the method declaration.)
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'string'", "attr.string(default=f)");
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'sequence'", "attr.string_list(default=f)");
    ev.checkEvalErrorContains("got value of type 'function', want 'int'", "attr.int(default=f)");
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'sequence'", "attr.int_list(default=f)");
    ev.checkEvalErrorContains("got value of type 'function', want 'bool'", "attr.bool(default=f)");
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'dict'", "attr.string_dict(default=f)");
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'dict'", "attr.string_list_dict(default=f)");
    // Note: attr.license appears to be disabled already.
    // (see --incompatible_no_attr_license)
  }

  private static final Label FAKE_LABEL = Label.parseAbsoluteUnchecked("//fake/label.bzl");

  @Test
  public void testRuleAddAttribute() throws Exception {
    evalAndExport(ev, "def impl(ctx): return None", "r1 = rule(impl, attrs={'a1': attr.string()})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.hasAttr("a1", Type.STRING)).isTrue();
  }

  private static void evalAndExport(BazelEvaluationTestCase ev, String... lines) throws Exception {
    ParserInput input = ParserInput.fromLines(lines);
    Module module = ev.getModule();
    StarlarkFile file = StarlarkFile.parse(input);
    Program prog = Program.compileFile(file, module);
    BzlLoadFunction.execAndExport(
        prog, FAKE_LABEL, ev.getEventHandler(), module, ev.getStarlarkThread());
  }

  @Test
  public void testExportAliasedName() throws Exception {
    // When there are multiple names aliasing the same StarlarkExportable, the first one to be
    // declared should be used. Make sure we're not using lexicographical order, hash order,
    // non-deterministic order, or anything else.
    evalAndExport(
        ev,
        "def _impl(ctx): pass",
        "d = rule(implementation = _impl)",
        "a = d",
        // Having more names improves the chance that non-determinism will be caught.
        "b = d",
        "c = d",
        "e = d",
        "f = d",
        "foo = d",
        "bar = d",
        "baz = d",
        "x = d",
        "y = d",
        "z = d");
    String dName = ((StarlarkRuleFunction) ev.lookup("d")).getRuleClass().getName();
    String fooName = ((StarlarkRuleFunction) ev.lookup("foo")).getRuleClass().getName();
    assertThat(dName).isEqualTo("d");
    assertThat(fooName).isEqualTo("d");
  }

  @Test
  public void testExportWithSpecifiedName() throws Exception {
    evalAndExport(
        ev, //
        "def _impl(ctx): pass",
        "a = rule(implementation = _impl, name = 'r')",
        "z = a");

    String aName = ((StarlarkRuleFunction) ev.lookup("a")).getRuleClass().getName();
    assertThat(aName).isEqualTo("r");
    String zName = ((StarlarkRuleFunction) ev.lookup("z")).getRuleClass().getName();
    assertThat(zName).isEqualTo("r");
  }

  @Test
  public void testExportWithSpecifiedNameFailure() throws Exception {
    ev.setFailFast(false);

    evalAndExport(
        ev, //
        "def _impl(ctx): pass",
        "rule(implementation = _impl, name = '1a')");

    ev.assertContainsError("Invalid rule name: 1a");
  }

  @Test
  public void testExportWithNonStringNameFailsCleanly() throws Exception {
    ev.setFailFast(false);

    evalAndExport(
        ev, //
        "def _impl(ctx): pass",
        "rule(implementation = _impl, name = {'not_a_string': True})");

    ev.assertContainsError("got value of type 'dict', want 'string or NoneType'");
  }

  @Test
  public void testExportWithMultipleErrors() throws Exception {
    ev.setFailFast(false);

    evalAndExport(
        ev,
        "def _impl(ctx): pass",
        "rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'name' : attr.string(),",
        "    'tags' : attr.string_list(),",
        "  },",
        "  name = '1a',",
        ")");

    ev.assertContainsError(
        "Error in rule: Errors in exporting 1a: \n"
            + "cannot add attribute: There is already a built-in attribute 'name' which cannot be"
            + " overridden.\n"
            + "cannot add attribute: There is already a built-in attribute 'tags' which cannot be"
            + " overridden.\n"
            + "Invalid rule name: 1a");
  }

  @Test
  public void testOutputToGenfiles() throws Exception {
    evalAndExport(ev, "def impl(ctx): pass", "r1 = rule(impl, output_to_genfiles=True)");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.outputsToBindir()).isFalse();
  }

  @Test
  public void testRuleAddMultipleAttributes() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl,",
        "     attrs = {",
        "            'a1': attr.label_list(allow_files=True),",
        "            'a2': attr.int()",
        "})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.hasAttr("a1", BuildType.LABEL_LIST)).isTrue();
    assertThat(c.hasAttr("a2", Type.INTEGER)).isTrue();
  }

  @Test
  public void testRuleAttributeFlag() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl, attrs = {'a1': attr.string(mandatory=True)})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.getAttributeByName("a1").isMandatory()).isTrue();
  }

  @Test
  public void unknownRuleAttributeFlags_forbidden() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def _impl(ctx): return None",
        "r1 = rule(_impl, attrs = { 'srcs': attr.label_list(flags = ['NO-SUCH-FLAG']) })");
    ev.assertContainsError("unknown attribute flag 'NO-SUCH-FLAG'");
  }

  @Test
  public void duplicateRuleAttributeFlags_forbidden() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def _impl(ctx): return None",
        "r1 = rule(_impl, attrs = { 'srcs': attr.label_list(mandatory = True,",
        "                                                   flags = ['MANDATORY']) })");
    ev.assertContainsError("'MANDATORY' flag is already set");
  }

  @Test
  public void testRuleOutputs() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None", //
        "r1 = rule(impl, outputs = {'a': 'a.txt'})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    ImplicitOutputsFunction function = c.getDefaultImplicitOutputsFunction();
    assertThat(function.getImplicitOutputs(ev.getEventHandler(), null)).containsExactly("a.txt");
  }

  @Test
  public void testRuleUnknownKeyword() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains(
        "unexpected keyword argument 'bad_keyword'", "rule(impl, bad_keyword = 'some text')");
  }

  @Test
  public void testRuleImplementationMissing() throws Exception {
    ev.checkEvalErrorContains(
        "rule() missing 1 required positional argument: implementation", "rule(attrs = {})");
  }

  @Test
  public void testRuleBadTypeForAdd() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains(
        "in call to rule(), parameter 'attrs' got value of type 'string', want 'dict or NoneType'",
        "rule(impl, attrs = 'some text')");
  }

  @Test
  public void testRuleBadTypeInAdd() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains(
        "got dict<string, string> for 'attrs', want dict<string, Attribute>",
        "rule(impl, attrs = {'a1': 'some text'})");
  }

  @Test
  public void testRuleBadTypeForDoc() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains("got value of type 'int', want 'string'", "rule(impl, doc = 1)");
  }

  @Test
  public void testLabel() throws Exception {
    Object result = ev.eval("Label('//foo/foo:foo')");
    assertThat(result).isInstanceOf(Label.class);
    assertThat(result.toString()).isEqualTo("//foo/foo:foo");
  }

  @Test
  public void testLabelSameInstance() throws Exception {
    Object l1 = ev.eval("Label('//foo/foo:foo')");
    // Implicitly creates a new pkgContext and environment, yet labels should be the same.
    Object l2 = ev.eval("Label('//foo/foo:foo')");
    assertThat(l1).isSameInstanceAs(l2);
  }

  @Test
  public void testLabelNameAndPackage() throws Exception {
    Object result = ev.eval("Label('//foo/bar:baz').name");
    assertThat(result).isEqualTo("baz");
    // NB: implicitly creates a new pkgContext and environments, yet labels should be the same.
    result = ev.eval("Label('//foo/bar:baz').package");
    assertThat(result).isEqualTo("foo/bar");
  }

  @Test
  public void testRuleLabelDefaultValue() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None\n"
            + "r1 = rule(impl, attrs = {'a1': "
            + "attr.label(default = Label('//foo:foo'), allow_files=True)})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    Attribute a = c.getAttributeByName("a1");
    assertThat(a.getDefaultValueUnchecked()).isInstanceOf(Label.class);
    assertThat(a.getDefaultValueUnchecked().toString()).isEqualTo("//foo:foo");
  }

  @Test
  public void testIntDefaultValue() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl, attrs = {'a1': attr.int(default = 40+2)})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    Attribute a = c.getAttributeByName("a1");
    assertThat(a.getDefaultValueUnchecked()).isEqualTo(StarlarkInt.of(42));
  }

  @Test
  public void testIntDefaultValueMustBeInt32() throws Exception {
    // This is a test of the loading phase. Move somewhere more appropriate.
    ev.checkEvalErrorContains(
        "for parameter 'default' of attribute '', got 4294967296, want value in signed 32-bit"
            + " range",
        "attr.int(default = 0x10000 * 0x10000)");
    ev.checkEvalErrorContains(
        "for element 0 of parameter 'default' of attribute '', got 4294967296, want value in"
            + " signed 32-bit range",
        "attr.int_list(default = [0x10000 * 0x10000])");
  }

  @Test
  public void testIntAttributeValueMustBeInt32() throws Exception {
    // This is a test of the loading phase. Move somewhere more appropriate.
    scratch.file(
        "p/inc.bzl", //
        "def _impl(ctx): pass",
        "r = rule(_impl, attrs = dict(i=attr.int()))");
    scratch.file(
        "p/BUILD", //
        "load('inc.bzl', 'r')",
        "r(name = 'p', i = 0x10000 * 0x10000)");
    AssertionError expected = assertThrows(AssertionError.class, () -> createRuleContext("//p"));
    assertThat(expected)
        .hasMessageThat()
        .contains(
            "for attribute 'i' in 'r' rule, got 4294967296, want value in signed 32-bit range");
  }

  @Test
  public void testIntegerConcatTruncates() throws Exception {
    // The Type.INTEGER.concat operator, as used to resolve select(int)+select(int)
    // after rule construction, has a range of int32.
    scratch.file(
        "p/BUILD", //
        "s = select({'//conditions:default': -0x7fffffff})", // -0x7fffffff + -0x7fffffff = 2
        "cc_test(name='c', shard_count = s+s)");
    StarlarkRuleContext context = createRuleContext("//p:c");
    assertThat(context.getAttr().getValue("shard_count")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testRuleInheritsBaseRuleAttributes() throws Exception {
    evalAndExport(ev, "def impl(ctx): return None", "r1 = rule(impl)");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.hasAttr("tags", Type.STRING_LIST)).isTrue();
    assertThat(c.hasAttr("visibility", BuildType.NODEP_LABEL_LIST)).isTrue();
    assertThat(c.hasAttr("deprecation", Type.STRING)).isTrue();
    assertThat(c.hasAttr(":action_listener", BuildType.LABEL_LIST))
        .isTrue(); // required for extra actions
  }

  private void checkTextMessage(String from, String... lines) throws Exception {
    String[] strings = lines.clone();
    Object result = ev.eval(from);
    String expect = "";
    if (strings.length > 0) {
      expect = Joiner.on("\n").join(lines) + "\n";
    }
    assertThat(result).isEqualTo(expect);
  }

  @Test
  public void testSimpleTextMessagesBooleanFields() throws Exception {
    checkTextMessage("struct(name=True).to_proto()", "name: true");
    checkTextMessage("struct(name=False).to_proto()", "name: false");
  }

  @Test
  public void testStructRestrictedOverrides() throws Exception {
    ev.checkEvalErrorContains(
        "cannot override built-in struct function 'to_json'", "struct(to_json='foo')");

    ev.checkEvalErrorContains(
        "cannot override built-in struct function 'to_proto'", "struct(to_proto='foo')");
  }

  @Test
  public void testSimpleTextMessages() throws Exception {
    checkTextMessage("struct(name='value').to_proto()", "name: \"value\"");
    checkTextMessage("struct(name=[]).to_proto()"); // empty lines
    checkTextMessage("struct(name=['a', 'b']).to_proto()", "name: \"a\"", "name: \"b\"");
    checkTextMessage("struct(name=123).to_proto()", "name: 123");
    checkTextMessage(
        "struct(a=1.2e34, b=float('nan'), c=float('-inf')).to_proto()",
        "a: 1.2e+34",
        "b: nan",
        "c: -inf");
    checkTextMessage("struct(name=123).to_proto()", "name: 123");
    checkTextMessage("struct(name=[1, 2, 3]).to_proto()", "name: 1", "name: 2", "name: 3");
    checkTextMessage("struct(a=struct(b='b')).to_proto()", "a {", "  b: \"b\"", "}");
    checkTextMessage(
        "struct(a=[struct(b='x'), struct(b='y')]).to_proto()",
        "a {",
        "  b: \"x\"",
        "}",
        "a {",
        "  b: \"y\"",
        "}");
    checkTextMessage(
        "struct(a=struct(b=struct(c='c'))).to_proto()", "a {", "  b {", "    c: \"c\"", "  }", "}");
    // dict to_proto tests
    checkTextMessage("struct(name={}).to_proto()"); // empty lines
    checkTextMessage(
        "struct(name={'a': 'b'}).to_proto()", "name {", "  key: \"a\"", "  value: \"b\"", "}");
    checkTextMessage(
        "struct(name={'c': 'd', 'a': 'b'}).to_proto()",
        "name {",
        "  key: \"c\"",
        "  value: \"d\"",
        "}",
        "name {",
        "  key: \"a\"",
        "  value: \"b\"",
        "}");
    checkTextMessage(
        "struct(x=struct(y={'a': 1})).to_proto()",
        "x {",
        "  y {",
        "    key: \"a\"",
        "    value: 1",
        "  }",
        "}");
    checkTextMessage(
        "struct(name={'a': struct(b=1, c=2)}).to_proto()",
        "name {",
        "  key: \"a\"",
        "  value {",
        "    b: 1",
        "    c: 2",
        "  }",
        "}");
    checkTextMessage(
        "struct(name={'a': struct(b={4: 'z', 3: 'y'}, c=2)}).to_proto()",
        "name {",
        "  key: \"a\"",
        "  value {",
        "    b {",
        "      key: 4",
        "      value: \"z\"",
        "    }",
        "    b {",
        "      key: 3",
        "      value: \"y\"",
        "    }",
        "    c: 2",
        "  }",
        "}");
  }

  @Test
  public void testProtoFieldsOrder() throws Exception {
    checkTextMessage("struct(d=4, b=2, c=3, a=1).to_proto()", "a: 1", "b: 2", "c: 3", "d: 4");
  }

  @Test
  public void testTextMessageEscapes() throws Exception {
    checkTextMessage("struct(name='a\"b').to_proto()", "name: \"a\\\"b\"");
    checkTextMessage("struct(name='a\\'b').to_proto()", "name: \"a'b\"");
    checkTextMessage("struct(name='a\\nb').to_proto()", "name: \"a\\nb\"");

    // struct(name="a\\\"b") -> name: "a\\\"b"
    checkTextMessage("struct(name='a\\\\\\\"b').to_proto()", "name: \"a\\\\\\\"b\"");
  }

  @Test
  public void testTextMessageInvalidStructure() throws Exception {
    // list in list
    ev.checkEvalErrorContains(
        "in struct field .a: at list index 0: got list, want string, int, bool, or struct",
        "struct(a=[['b']]).to_proto()");

    // dict in list
    ev.checkEvalErrorContains(
        "in struct field .a: at list index 0: got dict, want string, int, bool, or struct",
        "struct(a=[{'b': 1}]).to_proto()");

    // tuple as dict key
    ev.checkEvalErrorContains(
        "in struct field .a: invalid dict key: got tuple, want int or string",
        "struct(a={(1, 2): 3}).to_proto()");

    // dict in dict
    ev.checkEvalErrorContains(
        "in struct field .name: in value for dict key \"a\": got dict, want string, int, bool, or"
            + " struct",
        "struct(name={'a': {'b': [1, 2]}}).to_proto()");

    // callable in field
    ev.checkEvalErrorContains(
        "in struct field .a: got builtin_function_or_method, want string, int, bool, or struct",
        "struct(a=rule).to_proto()");
  }

  private void checkJson(String from, String expected) throws Exception {
    Object result = ev.eval(from);
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void testStarlarkJsonModule() throws Exception {
    // struct.to_json is deprecated.
    // java.starlark.net's json module is its replacement.
    setBuildLanguageOptions("--incompatible_struct_has_no_methods=false");
    checkJson("json.encode(struct(name=True))", "{\"name\":true}");
    checkJson("json.encode([1, 2])", "[1,2]"); // works for non-structs too
    checkJson("str(dir(struct()))", "[\"to_json\", \"to_proto\"]");

    setBuildLanguageOptions("--incompatible_struct_has_no_methods=true");
    ev.checkEvalErrorContains("no field or method 'to_json'", "struct(name=True).to_json()");
    checkJson("str(dir(struct()))", "[]"); // no to_{json,proto}
  }

  @Test
  public void testJsonBooleanFields() throws Exception {
    checkJson("struct(name=True).to_json()", "{\"name\":true}");
    checkJson("struct(name=False).to_json()", "{\"name\":false}");
  }

  @Test
  public void testJsonDictFields() throws Exception {
    checkJson("struct(config={}).to_json()", "{\"config\":{}}");
    checkJson("struct(config={'key': 'value'}).to_json()", "{\"config\":{\"key\":\"value\"}}");
    ev.checkEvalErrorContains(
        "Keys must be a string but got a int for struct field 'config'",
        "struct(config={1:2}).to_json()");
    ev.checkEvalErrorContains(
        "Keys must be a string but got a int for dict value 'foo'",
        "struct(config={'foo':{1:2}}).to_json()");
    ev.checkEvalErrorContains(
        "Keys must be a string but got a bool for struct field 'config'",
        "struct(config={True: False}).to_json()");
  }

  @Test
  public void testJsonEncoding() throws Exception {
    checkJson("struct(name='value').to_json()", "{\"name\":\"value\"}");
    checkJson("struct(name=['a', 'b']).to_json()", "{\"name\":[\"a\",\"b\"]}");
    checkJson("struct(name=123).to_json()", "{\"name\":123}");
    checkJson("struct(name=[1, 2, 3]).to_json()", "{\"name\":[1,2,3]}");
    checkJson("struct(a=struct(b='b')).to_json()", "{\"a\":{\"b\":\"b\"}}");
    checkJson("struct(a=[struct(b='x'), struct(b='y')]).to_json()",
        "{\"a\":[{\"b\":\"x\"},{\"b\":\"y\"}]}");
    checkJson("struct(a=struct(b=struct(c='c'))).to_json()",
        "{\"a\":{\"b\":{\"c\":\"c\"}}}");
  }

  @Test
  public void testJsonEscapes() throws Exception {
    checkJson("struct(name='a\"b').to_json()", "{\"name\":\"a\\\"b\"}");
    checkJson("struct(name='a\\'b').to_json()", "{\"name\":\"a'b\"}");
    checkJson("struct(name='a\\\\b').to_json()", "{\"name\":\"a\\\\b\"}");
    checkJson("struct(name='a\\nb').to_json()", "{\"name\":\"a\\nb\"}");
    checkJson("struct(name='a\\rb').to_json()", "{\"name\":\"a\\rb\"}");
    checkJson("struct(name='a\\tb').to_json()", "{\"name\":\"a\\tb\"}");
  }

  @Test
  public void testJsonNestedListStructure() throws Exception {
    checkJson("struct(a=[['b']]).to_json()", "{\"a\":[[\"b\"]]}");
  }

  @Test
  public void testJsonInvalidStructure() throws Exception {
    ev.checkEvalErrorContains(
        "Invalid text format, expected a struct, a string, a bool, or an int but got a "
            + "builtin_function_or_method for struct field 'a'",
        "struct(a=rule).to_json()");
  }

  @Test
  public void testLabelAttrWrongDefault() throws Exception {
    ev.checkEvalErrorContains(
        "got value of type 'int', want 'Label, string, LateBoundDefault, function, or NoneType'",
        "attr.label(default = 123)");
  }

  @Test
  public void testLabelGetRelative() throws Exception {
    assertThat(ev.eval("Label('//foo:bar').relative('baz')").toString()).isEqualTo("//foo:baz");
    assertThat(ev.eval("Label('//foo:bar').relative('//baz:qux')").toString())
        .isEqualTo("//baz:qux");
  }

  @Test
  public void testLabelGetRelativeSyntaxError() throws Exception {
    ev.checkEvalErrorContains(
        "invalid target name 'bad//syntax': target names may not contain '//' path separators",
        "Label('//foo:bar').relative('bad//syntax')");
  }

  @Test
  public void testStructCreation() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec("x = struct(a = 1, b = 2)");
    assertThat(ev.lookup("x")).isInstanceOf(Structure.class);
  }

  @Test
  public void testStructFields() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec("x = struct(a = 1, b = 2)");
    Structure x = (Structure) ev.lookup("x");
    assertThat(x.getValue("a")).isEqualTo(StarlarkInt.of(1));
    assertThat(x.getValue("b")).isEqualTo(StarlarkInt.of(2));

    // Update is prohibited.
    ev.checkEvalErrorContains(
        "struct value does not support field assignment", "x = struct(a = 1); x.a = 2");
  }

  @Test
  public void testStructEquality() throws Exception {
    assertThat((Boolean) ev.eval("struct(a = 1, b = 2) == struct(b = 2, a = 1)")).isTrue();
    assertThat((Boolean) ev.eval("struct(a = 1) == struct(a = 1, b = 2)")).isFalse();
    assertThat((Boolean) ev.eval("struct(a = 1, b = 2) == struct(a = 1)")).isFalse();
    // Compare a recursive object to itself to make sure reference equality is checked
    ev.exec("s = struct(a = 1, b = []); s.b.append(s)");
    assertThat((Boolean) ev.eval("s == s")).isTrue();
    assertThat((Boolean) ev.eval("struct(a = 1, b = 2) == struct(a = 1, b = 3)")).isFalse();
    assertThat((Boolean) ev.eval("struct(a = 1) == [1]")).isFalse();
    assertThat((Boolean) ev.eval("[1] == struct(a = 1)")).isFalse();
    assertThat((Boolean) ev.eval("struct() == struct()")).isTrue();
    assertThat((Boolean) ev.eval("struct() == struct(a = 1)")).isFalse();

    ev.exec("foo = provider(); bar = provider()");
    assertThat((Boolean) ev.eval("struct(a = 1) == foo(a = 1)")).isFalse();
    assertThat((Boolean) ev.eval("foo(a = 1) == struct(a = 1)")).isFalse();
    assertThat((Boolean) ev.eval("foo(a = 1) == bar(a = 1)")).isFalse();
    assertThat((Boolean) ev.eval("foo(a = 1) == foo(a = 1)")).isTrue();
  }

  @Test
  public void testStructIncomparability() throws Exception {
    ev.checkEvalErrorContains(
        "unsupported comparison: struct <=> struct", "struct(a = 1) < struct(a = 2)");
    ev.checkEvalErrorContains(
        "unsupported comparison: struct <=> struct", "struct(a = 1) > struct(a = 2)");
    ev.checkEvalErrorContains(
        "unsupported comparison: struct <=> struct", "struct(a = 1) <= struct(a = 2)");
    ev.checkEvalErrorContains(
        "unsupported comparison: struct <=> struct", "struct(a = 1) >= struct(a = 2)");
  }

  @Test
  public void testStructAccessingFieldsFromStarlark() throws Exception {
    ev.exec("x = struct(a = 1, b = 2)", "x1 = x.a", "x2 = x.b");
    assertThat(ev.lookup("x1")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("x2")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testStructAccessingUnknownField() throws Exception {
    ev.checkEvalErrorContains(
        "'struct' value has no field or method 'c'\n" + "Available attributes: a, b",
        "x = struct(a = 1, b = 2)",
        "y = x.c");
  }

  @Test
  public void testStructAccessingUnknownFieldWithArgs() throws Exception {
    ev.checkEvalErrorContains(
        "'struct' value has no field or method 'c'", "x = struct(a = 1, b = 2)", "y = x.c()");
  }

  @Test
  public void testStructAccessingNonFunctionFieldWithArgs() throws Exception {
    ev.checkEvalErrorContains(
        "'int' object is not callable", "x = struct(a = 1, b = 2)", "x1 = x.a(1)");
  }

  @Test
  public void testStructAccessingFunctionFieldWithArgs() throws Exception {
    ev.exec("def f(x): return x+5", "x = struct(a = f, b = 2)", "x1 = x.a(1)");
    assertThat(ev.lookup("x1")).isEqualTo(StarlarkInt.of(6));
  }

  @Test
  public void testStructPosArgs() throws Exception {
    ev.checkEvalErrorContains(
        "struct() got unexpected positional argument", "x = struct(1, b = 2)");
  }

  @Test
  public void testStructConcatenationFieldNames() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec(
        "x = struct(a = 1, b = 2)", //
        "y = struct(c = 1, d = 2)",
        "z = x + y\n");
    StructImpl z = (StructImpl) ev.lookup("z");
    assertThat(z.getFieldNames()).containsExactly("a", "b", "c", "d");
  }

  @Test
  public void testStructConcatenationFieldValues() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec(
        "x = struct(a = 1, b = 2)", //
        "y = struct(c = 1, d = 2)",
        "z = x + y\n");
    StructImpl z = (StructImpl) ev.lookup("z");
    assertThat(z.getValue("a")).isEqualTo(StarlarkInt.of(1));
    assertThat(z.getValue("b")).isEqualTo(StarlarkInt.of(2));
    assertThat(z.getValue("c")).isEqualTo(StarlarkInt.of(1));
    assertThat(z.getValue("d")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testStructConcatenationCommonFields() throws Exception {
    ev.checkEvalErrorContains(
        "cannot add struct instances with common field 'a'",
        "x = struct(a = 1, b = 2)",
        "y = struct(c = 1, a = 2)",
        "z = x + y\n");
  }

  @Test
  public void testConditionalStructConcatenation() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec(
        "def func():",
        "  x = struct(a = 1, b = 2)",
        "  if True:",
        "    x += struct(c = 1, d = 2)",
        "  return x",
        "x = func()");
    StructImpl x = (StructImpl) ev.lookup("x");
    assertThat(x.getValue("a")).isEqualTo(StarlarkInt.of(1));
    assertThat(x.getValue("b")).isEqualTo(StarlarkInt.of(2));
    assertThat(x.getValue("c")).isEqualTo(StarlarkInt.of(1));
    assertThat(x.getValue("d")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testGetattrNoAttr() throws Exception {
    ev.checkEvalErrorContains(
        "'struct' value has no field or method 'b'\nAvailable attributes: a",
        "s = struct(a='val')",
        "getattr(s, 'b')");
  }

  @Test
  public void testGetattr() throws Exception {
    ev.exec("s = struct(a='val')", "x = getattr(s, 'a')", "y = getattr(s, 'b', 'def')");
    assertThat(ev.lookup("x")).isEqualTo("val");
    assertThat(ev.lookup("y")).isEqualTo("def");
  }

  @Test
  public void testHasattr() throws Exception {
    ev.exec(
        "s = struct(a=1)", //
        "x = hasattr(s, 'a')",
        "y = hasattr(s, 'b')\n");
    assertThat(ev.lookup("x")).isEqualTo(true);
    assertThat(ev.lookup("y")).isEqualTo(false);
  }

  @Test
  public void testStructStr() throws Exception {
    assertThat(ev.eval("str(struct(x = 2, y = 3, z = 4))"))
        .isEqualTo("struct(x = 2, y = 3, z = 4)");
  }

  @Test
  public void testStructsInSets() throws Exception {
    ev.exec("depset([struct(a='a')])");
  }

  @Test
  public void testStructsInDicts() throws Exception {
    ev.exec("d = {struct(a = 1): 'aa', struct(b = 2): 'bb'}");
    assertThat(ev.eval("d[struct(a = 1)]")).isEqualTo("aa");
    assertThat(ev.eval("d[struct(b = 2)]")).isEqualTo("bb");
    assertThat(ev.eval("str([d[k] for k in d])")).isEqualTo("[\"aa\", \"bb\"]");

    ev.checkEvalErrorContains("unhashable type: 'struct'", "{struct(a = []): 'foo'}");
  }

  @Test
  public void testStructDictMembersAreMutable() throws Exception {
    ev.exec(
        "s = struct(x = {'a' : 1})", //
        "s.x['b'] = 2\n");
    assertThat(((StructImpl) ev.lookup("s")).getValue("x"))
        .isEqualTo(ImmutableMap.of("a", StarlarkInt.of(1), "b", StarlarkInt.of(2)));
  }

  @Test
  public void testDepsetGoodCompositeItem() throws Exception {
    ev.exec("def func():", "  return depset([struct(a='a')])", "s = func()");
    ImmutableList<?> result = ((Depset) ev.lookup("s")).toList();
    assertThat(result).hasSize(1);
    assertThat(result.get(0)).isInstanceOf(StructImpl.class);
  }

  private static StructImpl makeStruct(String field, Object value) {
    return StructProvider.STRUCT.create(ImmutableMap.of(field, value), "no field '%'");
  }

  private static StructImpl makeBigStruct(@Nullable Mutability mu) {
    // struct(a=[struct(x={1:1}), ()], b=(), c={2:2})
    return StructProvider.STRUCT.create(
        ImmutableMap.<String, Object>of(
            "a",
                StarlarkList.<Object>of(
                    mu,
                    StructProvider.STRUCT.create(
                        ImmutableMap.<String, Object>of("x", dictOf(mu, 1, 1)), "no field '%s'"),
                    Tuple.of()),
            "b", Tuple.of(),
            "c", dictOf(mu, 2, 2)),
        "no field '%s'");
  }

  private static Dict<Object, Object> dictOf(@Nullable Mutability mu, int k, int v) {
    return Dict.<Object, Object>builder().put(StarlarkInt.of(k), StarlarkInt.of(v)).build(mu);
  }

  @Test
  public void testStructMutabilityShallow() throws Exception {
    assertThat(Starlark.isImmutable(makeStruct("a", StarlarkInt.of(1)))).isTrue();
  }

  private static StarlarkList<Object> makeList(@Nullable Mutability mu) {
    return StarlarkList.<Object>of(mu, StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3));
  }

  @Test
  public void testStructMutabilityDeep() throws Exception {
    assertThat(Starlark.isImmutable(Tuple.of(makeList(null)))).isTrue();
    assertThat(Starlark.isImmutable(makeStruct("a", makeList(null)))).isTrue();
    assertThat(Starlark.isImmutable(makeBigStruct(null))).isTrue();

    Mutability mu = Mutability.create("test");
    assertThat(Starlark.isImmutable(Tuple.of(makeList(mu)))).isFalse();
    assertThat(Starlark.isImmutable(makeStruct("a", makeList(mu)))).isFalse();
    assertThat(Starlark.isImmutable(makeBigStruct(mu))).isFalse();
  }

  @Test
  public void declaredProviders() throws Exception {
    evalAndExport(ev, "data = provider()", "d = data(x = 1, y ='abc')", "d_x = d.x", "d_y = d.y");
    assertThat(ev.lookup("d_x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("d_y")).isEqualTo("abc");
    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");
    StructImpl data = (StructImpl) ev.lookup("d");
    assertThat(data.getProvider()).isEqualTo(dataConstructor);
    assertThat(dataConstructor.isExported()).isTrue();
    assertThat(dataConstructor.getPrintableName()).isEqualTo("data");
    assertThat(dataConstructor.getKey()).isEqualTo(new StarlarkProvider.Key(FAKE_LABEL, "data"));
  }

  @Test
  public void declaredProvidersWithInit() throws Exception {
    evalAndExport(
        ev,
        "def _data_init(x, y = 'abc'):", //
        "    return {'x': x, 'y': y}",
        "data, _new_data = provider(init = _data_init)",
        "d1 = data(x = 1)  # normal provider constructor",
        "d1_x = d1.x",
        "d1_y = d1.y",
        "d2 = data(1, 'def')  # normal provider constructor invoked with positional arguments",
        "d2_x = d2.x",
        "d2_y = d2.y",
        "d3 = _new_data(x = 2, y = 'xyz')  # raw constructor",
        "d3_x = d3.x",
        "d3_y = d3.y");

    assertThat(ev.lookup("d1_x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("d1_y")).isEqualTo("abc");
    assertThat(ev.lookup("d2_x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("d2_y")).isEqualTo("def");
    assertThat(ev.lookup("d3_x")).isEqualTo(StarlarkInt.of(2));
    assertThat(ev.lookup("d3_y")).isEqualTo("xyz");
    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");
    StarlarkCallable rawConstructor = (StarlarkCallable) ev.lookup("_new_data");
    assertThat(rawConstructor).isNotInstanceOf(Provider.class);
    assertThat(dataConstructor.getInit().getName()).isEqualTo("_data_init");

    StructImpl data1 = (StructImpl) ev.lookup("d1");
    StructImpl data2 = (StructImpl) ev.lookup("d2");
    StructImpl data3 = (StructImpl) ev.lookup("d3");
    assertThat(data1.getProvider()).isEqualTo(dataConstructor);
    assertThat(data2.getProvider()).isEqualTo(dataConstructor);
    assertThat(data3.getProvider()).isEqualTo(dataConstructor);
    assertThat(dataConstructor.isExported()).isTrue();
    assertThat(dataConstructor.getPrintableName()).isEqualTo("data");
    assertThat(dataConstructor.getKey()).isEqualTo(new StarlarkProvider.Key(FAKE_LABEL, "data"));
  }

  @Test
  public void declaredProvidersWithFailingInit_rawConstructorSucceeds() throws Exception {
    evalAndExport(
        ev,
        "def _data_failing_init(x):", //
        "    fail('_data_failing_init fails')",
        "data, _new_data = provider(init = _data_failing_init)");

    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");

    evalAndExport(ev, "d = _new_data(x = 1)  # raw constructor");
    StructImpl data = (StructImpl) ev.lookup("d");
    assertThat(data.getProvider()).isEqualTo(dataConstructor);
  }

  @Test
  public void declaredProvidersWithFailingInit_normalConstructorFails() throws Exception {
    evalAndExport(
        ev,
        "def _data_failing_init(x):", //
        "    fail('_data_failing_init fails')",
        "data, _new_data = provider(init = _data_failing_init)");

    ev.checkEvalErrorContains("_data_failing_init fails", "d = data(x = 1)  # normal constructor");
    assertThat(ev.lookup("d")).isNull();
  }

  @Test
  public void declaredProvidersWithInitReturningInvalidType_normalConstructorFails()
      throws Exception {
    evalAndExport(
        ev,
        "def _data_invalid_init(x):", //
        "    return 'INVALID'",
        "data, _new_data = provider(init = _data_invalid_init)");

    ev.checkEvalErrorContains(
        "got string for 'return value of provider init()', want dict",
        "d = data(x = 1)  # normal constructor");
    assertThat(ev.lookup("d")).isNull();
  }

  @Test
  public void declaredProvidersWithInitReturningInvalidDict_normalConstructorFails()
      throws Exception {
    evalAndExport(
        ev,
        "def _data_invalid_init(x):", //
        "    return {('x', 'x', 'x'): x}",
        "data, _new_data = provider(init = _data_invalid_init)");

    ev.checkEvalErrorContains(
        "got dict<tuple, int> for 'return value of provider init()'",
        "d = data(x = 1)  # normal constructor");
    assertThat(ev.lookup("d")).isNull();
  }

  @Test
  public void declaredProvidersWithInitReturningUnexpectedFields_normalConstructorFails()
      throws Exception {
    evalAndExport(
        ev,
        "def _data_unexpected_fields_init(x):", //
        "    return {'x': x, 'y': x * 2}",
        "data, _new_data = provider(fields = ['x'], init = _data_unexpected_fields_init)");

    ev.checkEvalErrorContains(
        "got unexpected field 'y' in call to instantiate provider data",
        "d = data(x = 1)  # normal constructor");
    assertThat(ev.lookup("d")).isNull();
  }

  @Test
  public void declaredProvidersConcatSuccess() throws Exception {
    evalAndExport(
        ev,
        "data = provider()",
        "dx = data(x = 1)",
        "dy = data(y = 'abc')",
        "dxy = dx + dy",
        "x = dxy.x",
        "y = dxy.y");
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("y")).isEqualTo("abc");
    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");
    StructImpl dx = (StructImpl) ev.lookup("dx");
    assertThat(dx.getProvider()).isEqualTo(dataConstructor);
    StructImpl dy = (StructImpl) ev.lookup("dy");
    assertThat(dy.getProvider()).isEqualTo(dataConstructor);
  }

  @Test
  public void declaredProvidersWithInitConcatSuccess() throws Exception {
    evalAndExport(
        ev,
        "def _data_init(x):",
        "    return {'x': x}",
        "data, _new_data = provider(init = _data_init)",
        "dx = data(x = 1)  # normal constructor",
        "dy = _new_data(y = 'abc')  # raw constructor",
        "dxy = dx + dy",
        "x = dxy.x",
        "y = dxy.y");
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("y")).isEqualTo("abc");
    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");
    StructImpl dx = (StructImpl) ev.lookup("dx");
    assertThat(dx.getProvider()).isEqualTo(dataConstructor);
    StructImpl dy = (StructImpl) ev.lookup("dy");
    assertThat(dy.getProvider()).isEqualTo(dataConstructor);
  }

  @Test
  public void declaredProvidersConcatError() throws Exception {
    evalAndExport(ev, "data1 = provider()", "data2 = provider()");

    ev.checkEvalErrorContains(
        "Cannot use '+' operator on instances of different providers (data1 and data2)",
        "d1 = data1(x = 1)",
        "d2 = data2(y = 2)",
        "d = d1 + d2");
  }

  @Test
  public void declaredProvidersWithFieldsConcatSuccess() throws Exception {
    evalAndExport(
        ev,
        "data = provider(fields=['f1', 'f2'])",
        "d1 = data(f1 = 4)",
        "d2 = data(f2 = 5)",
        "d3 = d1 + d2",
        "f1 = d3.f1",
        "f2 = d3.f2");
    assertThat(ev.lookup("f1")).isEqualTo(StarlarkInt.of(4));
    assertThat(ev.lookup("f2")).isEqualTo(StarlarkInt.of(5));
  }

  @Test
  public void declaredProvidersWithFieldsConcatError() throws Exception {
    evalAndExport(ev, "data1 = provider(fields=['f1', 'f2'])", "data2 = provider(fields=['f3'])");
    ev.checkEvalErrorContains(
        "Cannot use '+' operator on instances of different providers (data1 and data2)",
        "d1 = data1(f1=1, f2=2)",
        "d2 = data2(f3=3)",
        "d = d1 + d2");
  }

  @Test
  public void declaredProvidersWithOverlappingFieldsConcatError() throws Exception {
    evalAndExport(ev, "data = provider(fields=['f1', 'f2'])");
    ev.checkEvalErrorContains(
        "cannot add struct instances with common field 'f1'",
        "d1 = data(f1 = 4)",
        "d2 = data(f1 = 5)",
        "d1 + d2");
  }

  @Test
  public void structsAsDeclaredProvidersTest() throws Exception {
    evalAndExport(ev, "data = struct(x = 1)");
    StructImpl data = (StructImpl) ev.lookup("data");
    assertThat(StructProvider.STRUCT.isExported()).isTrue();
    assertThat(data.getProvider()).isEqualTo(StructProvider.STRUCT);
    assertThat(data.getProvider().getKey()).isEqualTo(StructProvider.STRUCT.getKey());
  }

  @Test
  public void declaredProvidersDoc() throws Exception {
    evalAndExport(ev, "data1 = provider(doc='foo')");
  }

  @Test
  public void declaredProvidersBadTypeForDoc() throws Exception {
    ev.checkEvalErrorContains("got value of type 'int', want 'string'", "provider(doc = 1)");
  }

  @Test
  public void aspectAllAttrs() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):", //
        "   pass",
        "my_aspect = aspect(_impl, attr_aspects=['*'])");

    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(myAspect.getDefinition(AspectParameters.EMPTY).propagateAlong("foo")).isTrue();
  }

  @Test
  public void aspectRequiredAspectProvidersSingle() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_aspect_providers=['java', cc])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder()
                    .addStarlark(declared("cc"))
                    .addStarlark("java")
                    .build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("cc").build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredAspectProvidersAlternatives() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_aspect_providers=[['java'], [cc]])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("java").build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("prolog").build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredAspectProvidersEmpty() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl, required_aspect_providers=[])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isFalse();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
  }

  @Test
  public void aspectRequiredAspectProvidersDefault() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):", //
        "   pass",
        "my_aspect = aspect(_impl)");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isFalse();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
  }

  @Test
  public void aspectRequiredProvidersNotAllowedWithApplyToGeneratingRules() throws Exception {
    ev.checkEvalErrorContains(
        "An aspect cannot simultaneously have required providers and apply to generating rules.",
        "prov = provider()",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   required_providers = [prov],",
        "   apply_to_generating_rules = True",
        ")");
  }

  @Test
  public void aspectRequiredProvidersSingle() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_providers=['java', cc])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders();

    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder()
                    .addStarlark(declared("cc"))
                    .addStarlark("java")
                    .build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredProvidersAlternatives() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_providers=[['java'], [cc]])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders();

    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("java").build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("prolog").build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredProvidersEmpty() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl, required_providers=[])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders();

    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isTrue();
  }

  @Test
  public void aspectRequiredProvidersDefault() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):", //
        "   pass",
        "my_aspect = aspect(_impl)");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders();

    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isTrue();
  }

  @Test
  public void aspectProvides() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "y = provider()",
        "my_aspect = aspect(_impl, provides = ['x', y])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    AdvertisedProviderSet advertisedProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getAdvertisedProviders();
    assertThat(advertisedProviders.canHaveAnyProvider()).isFalse();
    assertThat(advertisedProviders.getStarlarkProviders())
        .containsExactly(legacy("x"), declared("y"));
  }

  @Test
  public void aspectProvidesError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "y = provider()",
        "my_aspect = aspect(_impl, provides = ['x', 1])");
    MoreAsserts.assertContainsEvent(ev.getEventCollector(),
        " Illegal argument: element in 'provides' is of unexpected type."
            + " Should be list of providers, but got item of type int. ");
  }

  @Test
  public void aspectDoc() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):", //
        "   pass",
        "my_aspect = aspect(_impl, doc='foo')");
  }

  @Test
  public void aspectBadTypeForDoc() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains("got value of type 'int', want 'string'", "aspect(impl, doc = 1)");
  }

  @Test
  public void fancyExports() throws Exception {
    evalAndExport(
        ev,
        "def _impla(target, ctx): pass",
        "p, (a, p1) = [",
        "   provider(),",
        "   [ aspect(_impla),",
        "     provider() ]",
        "]");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkDefinedAspect a = (StarlarkDefinedAspect) ev.lookup("a");
    StarlarkProvider p1 = (StarlarkProvider) ev.lookup("p1");
    assertThat(p.getPrintableName()).isEqualTo("p");
    assertThat(p.getKey()).isEqualTo(new StarlarkProvider.Key(FAKE_LABEL, "p"));
    assertThat(p1.getPrintableName()).isEqualTo("p1");
    assertThat(p1.getKey()).isEqualTo(new StarlarkProvider.Key(FAKE_LABEL, "p1"));
    assertThat(a.getAspectClass()).isEqualTo(new StarlarkAspectClass(FAKE_LABEL, "a"));
  }

  @Test
  public void multipleTopLevels() throws Exception {
    evalAndExport(
        ev,
        "p = provider()", //
        "p1 = p");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkProvider p1 = (StarlarkProvider) ev.lookup("p1");
    assertThat(p).isEqualTo(p1);
    assertThat(p.getKey()).isEqualTo(new StarlarkProvider.Key(FAKE_LABEL, "p"));
    assertThat(p1.getKey()).isEqualTo(new StarlarkProvider.Key(FAKE_LABEL, "p"));
  }

  @Test
  public void providerWithFields() throws Exception {
    evalAndExport(
        ev,
        "p = provider(fields = ['x', 'y'])", //
        "p1 = p(x = 1, y = 2)",
        "x = p1.x",
        "y = p1.y");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkInfo p1 = (StarlarkInfo) ev.lookup("p1");

    assertThat(p1.getProvider()).isEqualTo(p);
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void providerWithFieldsDict() throws Exception {
    evalAndExport(
        ev,
        "p = provider(fields = { 'x' : 'I am x', 'y' : 'I am y'})",
        "p1 = p(x = 1, y = 2)",
        "x = p1.x",
        "y = p1.y");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkInfo p1 = (StarlarkInfo) ev.lookup("p1");

    assertThat(p1.getProvider()).isEqualTo(p);
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void providerWithFieldsOptional() throws Exception {
    evalAndExport(
        ev,
        "p = provider(fields = ['x', 'y'])", //
        "p1 = p(y = 2)",
        "y = p1.y");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkInfo p1 = (StarlarkInfo) ev.lookup("p1");

    assertThat(p1.getProvider()).isEqualTo(p);
    assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void providerWithFieldsOptionalError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "p = provider(fields = ['x', 'y'])", //
        "p1 = p(y = 2)",
        "x = p1.x");
    MoreAsserts.assertContainsEvent(
        ev.getEventCollector(), " 'p' value has no field or method 'x'");
  }

  @Test
  public void providerWithExtraFieldsError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(ev, "p = provider(fields = ['x', 'y'])", "p1 = p(x = 1, y = 2, z = 3)");
    MoreAsserts.assertContainsEvent(
        ev.getEventCollector(), "got unexpected field 'z' in call to instantiate provider p");
  }

  @Test
  public void providerWithEmptyFieldsError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "p = provider(fields = [])", //
        "p1 = p(x = 1, y = 2, z = 3)");
    MoreAsserts.assertContainsEvent(
        ev.getEventCollector(),
        "got unexpected fields 'x', 'y', 'z' in call to instantiate provider p");
  }

  @Test
  public void providerWithDuplicateFieldsError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "p = provider(fields = ['a', 'b'])", //
        "p(a = 1, b = 2, **dict(b = 3))");
    MoreAsserts.assertContainsEvent(
        ev.getEventCollector(),
        "got multiple values for parameter b in call to instantiate provider p");
  }

  @Test
  public void starTheOnlyAspectArg() throws Exception {
    ev.checkEvalErrorContains(
        "'*' must be the only string in 'attr_aspects' list",
        "def _impl(target, ctx):",
        "   pass",
        "aspect(_impl, attr_aspects=['*', 'foo'])");
  }

  @Test
  public void testMandatoryConfigParameterForExecutableLabels() throws Exception {
    scratch.file("third_party/foo/extension.bzl",
      "def _main_rule_impl(ctx):",
      "    pass",
      "my_rule = rule(_main_rule_impl,",
      "    attrs = { ",
      "        'exe' : attr.label(executable = True, allow_files = True),",
      "    },",
      ")"
    );
    scratch.file("third_party/foo/BUILD",
      "load(':extension.bzl', 'my_rule')",
      "my_rule(name = 'main', exe = ':tool.sh')"
    );

    AssertionError expected =
        assertThrows(AssertionError.class, () -> createRuleContext("//third_party/foo:main"));
    assertThat(expected).hasMessageThat()
        .contains("cfg parameter is mandatory when executable=True is provided.");
  }

  @Test
  public void testRuleAddToolchain() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl,",
        "    toolchains=[",
        "        '//test:my_toolchain_type1',",
        "        config_common.toolchain_type('//test:my_toolchain_type2'),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
        "        config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
        "    ],",
        ")");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c).hasToolchainType("//test:my_toolchain_type1");
    assertThat(c).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(c).hasToolchainType("//test:my_toolchain_type2");
    assertThat(c).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(c).hasToolchainType("//test:my_toolchain_type3");
    assertThat(c).toolchainType("//test:my_toolchain_type3").isOptional();
    assertThat(c).hasToolchainType("//test:my_toolchain_type4");
    assertThat(c).toolchainType("//test:my_toolchain_type4").isMandatory();
  }

  @Test
  public void testRuleAddToolchain_duplicate() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl,",
        "    toolchains=[",
        "        '//test:my_toolchain_type1',",
        "        config_common.toolchain_type('//test:my_toolchain_type1'),",
        "        config_common.toolchain_type('//test:my_toolchain_type2', mandatory = False),",
        "        config_common.toolchain_type('//test:my_toolchain_type2', mandatory = True),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory = False),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory = False),",
        "    ],",
        ")");

    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c).hasToolchainType("//test:my_toolchain_type1");
    assertThat(c).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(c).hasToolchainType("//test:my_toolchain_type2");
    assertThat(c).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(c).hasToolchainType("//test:my_toolchain_type3");
    assertThat(c).toolchainType("//test:my_toolchain_type3").isOptional();
  }

  @Test
  public void testRuleAddExecutionConstraints() throws Exception {
    registerDummyStarlarkFunction();
    evalAndExport(
        ev,
        "r1 = rule(",
        "  implementation = impl,",
        "  exec_compatible_with=['//constraint:cv1', '//constraint:cv2'],",
        ")");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseAbsoluteUnchecked("//constraint:cv1"),
            Label.parseAbsoluteUnchecked("//constraint:cv2"));
  }

  @Test
  public void testRuleAddExecGroup() throws Exception {
    registerDummyStarlarkFunction();
    evalAndExport(
        ev,
        "plum = rule(",
        "  implementation = impl,",
        "  exec_groups = {",
        "    'group': exec_group(",
        "      toolchains=[",
        "        '//test:my_toolchain_type1',",
        "        config_common.toolchain_type('//test:my_toolchain_type2'),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
        "        config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
        "      ],",
        "      exec_compatible_with=['//constraint:cv1', '//constraint:cv2'],",
        "    ),",
        "  },",
        ")");
    RuleClass plum = ((StarlarkRuleFunction) ev.lookup("plum")).getRuleClass();
    assertThat(plum.getToolchainTypes()).isEmpty();
    ExecGroup execGroup = plum.getExecGroups().get("group");
    assertThat(execGroup).hasToolchainType("//test:my_toolchain_type1");
    assertThat(execGroup).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(execGroup).hasToolchainType("//test:my_toolchain_type2");
    assertThat(execGroup).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(execGroup).hasToolchainType("//test:my_toolchain_type3");
    assertThat(execGroup).toolchainType("//test:my_toolchain_type3").isOptional();
    assertThat(execGroup).hasToolchainType("//test:my_toolchain_type4");
    assertThat(execGroup).toolchainType("//test:my_toolchain_type4").isMandatory();

    assertThat(plum.getExecutionPlatformConstraints()).isEmpty();
    assertThat(execGroup).hasExecCompatibleWith("//constraint:cv1");
    assertThat(execGroup).hasExecCompatibleWith("//constraint:cv2");
  }

  @Test
  public void testRuleFunctionReturnsNone() throws Exception {
    scratch.file("test/rule.bzl",
        "def _impl(ctx):",
        "  pass",
        "foo_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {'params': attr.string_list()},",
        ")");
    scratch.file("test/BUILD",
        "load(':rule.bzl', 'foo_rule')",
        "r = foo_rule(name='foo')",  // Custom rule should return None
        "c = cc_library(name='cc')", // Native rule should return None
        "",
        "foo_rule(",
        "    name='check',",
        "    params = [type(r), type(c)]",
        ")");
    invalidatePackages();
    StarlarkRuleContext context = createRuleContext("//test:check");
    @SuppressWarnings("unchecked")
    StarlarkList<Object> params = (StarlarkList<Object>) context.getAttr().getValue("params");
    assertThat(params.get(0)).isEqualTo("NoneType");
    assertThat(params.get(1)).isEqualTo("NoneType");
  }

  @Test
  public void testTypeOfStruct() throws Exception {
    ev.exec("p = type(struct)", "s = type(struct())");

    assertThat(ev.lookup("p")).isEqualTo("Provider");
    assertThat(ev.lookup("s")).isEqualTo("struct");
  }

  @Test
  public void testCreateExecGroup() throws Exception {
    evalAndExport(
        ev,
        "group = exec_group(",
        "  toolchains=[",
        "    '//test:my_toolchain_type1',",
        "    config_common.toolchain_type('//test:my_toolchain_type2'),",
        "    config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
        "    config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
        "  ],",
        "  exec_compatible_with=['//constraint:cv1', '//constraint:cv2'],",
        ")");
    ExecGroup group = ((ExecGroup) ev.lookup("group"));
    assertThat(group).hasToolchainType("//test:my_toolchain_type1");
    assertThat(group).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(group).hasToolchainType("//test:my_toolchain_type2");
    assertThat(group).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(group).hasToolchainType("//test:my_toolchain_type3");
    assertThat(group).toolchainType("//test:my_toolchain_type3").isOptional();
    assertThat(group).hasToolchainType("//test:my_toolchain_type4");
    assertThat(group).toolchainType("//test:my_toolchain_type4").isMandatory();

    assertThat(group).hasExecCompatibleWith("//constraint:cv1");
    assertThat(group).hasExecCompatibleWith("//constraint:cv2");
  }

  @Test
  public void ruleDefinitionEnvironmentDigest_unaffectedByTargetAttrValueChange() throws Exception {
    scratch.file(
        "r/def.bzl",
        "def _r(ctx): return struct(value=ctx.attr.text)",
        "r = rule(implementation=_r, attrs={'text': attr.string()})");
    scratch.file("r/BUILD", "load(':def.bzl', 'r')", "r(name='r', text='old')");
    byte[] oldDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    scratch.deleteFile("r/BUILD");
    scratch.file("r/BUILD", "load(':def.bzl', 'r')", "r(name='r', text='new')");
    // Signal SkyFrame to discover changed files.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);
    byte[] newDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    assertThat(newDigest).isEqualTo(oldDigest);
  }

  @Test
  public void ruleDefinitionEnvironmentDigest_accountsForFunctionWhenCreatingRuleWithAMacro()
      throws Exception {
    scratch.file("r/create.bzl", "def create(impl): return rule(implementation=impl)");
    scratch.file(
        "r/def.bzl",
        "load(':create.bzl', 'create')",
        "def f(ctx): return struct(value='OLD')",
        "r = create(f)");
    scratch.file("r/BUILD", "load(':def.bzl', 'r')", "r(name='r')");
    byte[] oldDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    scratch.deleteFile("r/def.bzl");
    scratch.file(
        "r/def.bzl",
        "load(':create.bzl', 'create')",
        "def f(ctx): return struct(value='NEW')",
        "r = create(f)");
    // Signal SkyFrame to discover changed files.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);
    byte[] newDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    assertThat(newDigest).isNotEqualTo(oldDigest);
  }

  @Test
  public void ruleDefinitionEnvironmentDigest_accountsForAttrsWhenCreatingRuleWithMacro()
      throws Exception {
    scratch.file(
        "r/create.bzl",
        "def f(ctx): return struct(value=ctx.attr.to_json())",
        "def create(attrs): return rule(implementation=f, attrs=attrs)");
    scratch.file("r/def.bzl", "load(':create.bzl', 'create')", "r = create({})");
    scratch.file("r/BUILD", "load(':def.bzl', 'r')", "r(name='r')");
    byte[] oldDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    scratch.deleteFile("r/def.bzl");
    scratch.file(
        "r/def.bzl",
        "load(':create.bzl', 'create')",
        "r = create({'value': attr.string(default='')})");
    // Signal SkyFrame to discover changed files.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);
    byte[] newDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    assertThat(newDigest).isNotEqualTo(oldDigest);
  }

  /**
   * This test is crucial for correctness of {@link RuleClass#getRuleDefinitionEnvironmentDigest}
   * since we use a dummy bzl transitive digest in that case. It is correct to do that only because
   * a rule class created by a BUILD thread cannot be instantiated.
   */
  @Test
  public void ruleClassDefinedInBuildFile_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    scratch.file("r/create.bzl", "def create(impl): return rule(implementation=impl)");
    scratch.file("r/def.bzl", "load(':create.bzl', 'create')", "r = create({})");
    scratch.file("r/impl.bzl", "def make_struct(ctx): return struct(value='hello')");
    scratch.file(
        "r/BUILD",
        "load(':create.bzl', 'create')",
        "load(':impl.bzl', 'make_struct')",
        "r = create(make_struct)",
        "r(name='r')");

    getConfiguredTarget("//r:r");

    ev.assertContainsError(
        "Error in unexported rule: Invalid rule class hasn't been exported by a bzl file");
  }

  @Test
  public void testAttrWithAspectRequiringAspects_requiredNativeAspect_getsParamsFromBaseRules()
      throws Exception {
    scratch.file(
        "lib.bzl",
        "rule_prov = provider()",
        "def _impl(target, ctx):",
        "   pass",
        "aspect_a = aspect(implementation = _impl,",
        "                  requires = [parametrized_native_aspect],",
        "                  attr_aspects = ['deps'],",
        "                  required_providers = [rule_prov])",
        "def impl(ctx):",
        "   return None",
        "my_rule = rule(impl,",
        "               attrs={'deps': attr.label_list(aspects = [aspect_a]),",
        "                      'aspect_attr': attr.string()})");
    scratch.file(
        "BUILD", "load(':lib.bzl', 'my_rule')", "my_rule(name = 'main', aspect_attr = 'v1')");

    RuleContext ruleContext = createRuleContext("//:main").getRuleContext();

    Rule rule = ruleContext.getRule();
    Attribute attr = rule.getRuleClassObject().getAttributeByName("deps");
    ImmutableList<Aspect> aspects = attr.getAspects(rule);
    Aspect requiredNativeAspect = aspects.get(0);
    assertThat(requiredNativeAspect.getAspectClass().getName())
        .isEqualTo("ParametrizedAspectWithProvider");
    assertThat(
            requiredNativeAspect
                .getDefinition()
                .getAttributes()
                .get("aspect_attr")
                .getDefaultValueUnchecked())
        .isEqualTo("v1");
  }
}
