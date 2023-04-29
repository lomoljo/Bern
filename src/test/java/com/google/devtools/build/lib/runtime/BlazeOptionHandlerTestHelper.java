// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.bazel.rules.BazelRulesModule;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.devtools.common.options.TestOptions;
import java.util.List;
import javax.annotation.Nullable;

/** Helper class for setting up tests that make use of {@link BlazeOptionHandler}. */
public class BlazeOptionHandlerTestHelper {

  private final Scratch scratch = new Scratch();
  private final StoredEventHandler eventHandler = new StoredEventHandler();
  private final OptionsParser parser;
  private final BlazeOptionHandler optionHandler;

  public BlazeOptionHandlerTestHelper(
      List<Class<? extends OptionsBase>> optionsClasses,
      boolean allowResidue,
      @Nullable String aliasFlag,
      boolean skipStarlarkPrefixes,
      InvocationPolicy invocationPolicy)
      throws Exception {
    parser = createOptionsParser(optionsClasses, allowResidue, aliasFlag, skipStarlarkPrefixes);

    String productName = TestConstants.PRODUCT_NAME;
    ServerDirectories serverDirectories =
        new ServerDirectories(
            scratch.dir("install_base"), scratch.dir("output_base"), scratch.dir("user_root"));

    BlazeRuntime runtime =
        new BlazeRuntime.Builder()
            .setFileSystem(scratch.getFileSystem())
            .setServerDirectories(serverDirectories)
            .setProductName(productName)
            .setStartupOptionsProvider(
                OptionsParser.builder().optionsClasses(BlazeServerStartupOptions.class).build())
            .addBlazeModule(new BazelRulesModule())
            .build();
    runtime.overrideCommands(ImmutableList.of(new C0Command(), new ParentCommand()));

    BlazeDirectories directories =
        new BlazeDirectories(
            serverDirectories,
            scratch.dir("workspace"),
            /* defaultSystemJavabase= */ null,
            productName);
    runtime.initWorkspace(directories, /*binTools=*/ null);

    optionHandler =
        new BlazeOptionHandler(
            runtime,
            runtime.getWorkspace(),
            new C0Command(),
            C0Command.class.getAnnotation(Command.class),
            parser,
            invocationPolicy);
  }

  private static OptionsParser createOptionsParser(
      List<Class<? extends OptionsBase>> optionsClasses,
      boolean allowResidue,
      @Nullable String aliasFlag,
      boolean skipStarlarkPrefixes) {

    OptionsParser.Builder optionsParserBuilder =
        OptionsParser.builder()
            .optionsClasses(optionsClasses)
            .allowResidue(allowResidue)
            .withAliasFlag(aliasFlag);

    if (skipStarlarkPrefixes) {
      optionsParserBuilder.skipStarlarkOptionPrefixes();
    }

    return optionsParserBuilder.build();
  }

  public OptionsParser getOptionsParser() {
    return parser;
  }

  public StoredEventHandler getEventHandler() {
    return eventHandler;
  }

  public BlazeOptionHandler getOptionHandler() {
    return optionHandler;
  }

  /** Custom command for testing. */
  @Command(
      name = "c0",
      shortDescription = "c0 desc",
      help = "c0 help",
      options = {TestOptions.class},
      inherits = ParentCommand.class)
  protected static class C0Command implements BlazeCommand {
    @Override
    public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void editOptions(OptionsParser optionsParser) {}
  }

  /** Custom command for testing. */
  @Command(
      name = "parent",
      shortDescription = "parent desc",
      help = "parent help",
      options = {ParentOptions.class})
  public static class ParentCommand implements BlazeCommand {
    @Override
    public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void editOptions(OptionsParser optionsParser) {}
  }

  public static class ParentOptions extends OptionsBase {
    public static final String TEST_STRING_DEFAULT = "parent test string default";

    @Option(
        name = "parent_test_string",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = TEST_STRING_DEFAULT,
        help = "a string-valued option to test simple option operations")
    public String testString;

    @Option(
        name = "parent_test_string2",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = TEST_STRING_DEFAULT,
        help = "another string-valued option to test simple option operations")
    public String testString2;
  }
}
