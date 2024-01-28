// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod.modcommand;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ExtensionArg.CommaSeparatedExtensionArgListConverter;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModuleArg.CommaSeparatedModuleArgListConverter;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModuleArg.ModuleArgConverter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;

/** Options for ModCommand */
public class ModOptions extends OptionsBase {

  @Option(
      name = "from",
      defaultValue = "<root>",
      converter = CommaSeparatedModuleArgListConverter.class,
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "The module(s) starting from which the dependency graph query will be displayed. Check"
              + " each query’s description for the exact semantics. Defaults to <root>.\n")
  public ImmutableList<ModuleArg> modulesFrom;

  @Option(
      name = "verbose",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "The queries will also display the reason why modules were resolved to their current"
              + " version (if changed). Defaults to true only for the explain query.")
  public boolean verbose;

  @Option(
      name = "include_unused",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "The queries will also take into account and display the unused modules, which are not"
              + " present in the module resolution graph after selection (due to the"
              + " Minimal-Version Selection or override rules). This can have different effects for"
              + " each of the query types i.e. include new paths in the all_paths command, or extra"
              + " dependants in the explain command.")
  public boolean includeUnused;

  @Option(
      name = "extension_filter",
      defaultValue = "null",
      converter = CommaSeparatedExtensionArgListConverter.class,
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Only display the usages of these module extensions and the repos generated by them if"
              + " their respective flags are set. If set, the result graph will only include paths"
              + " that contain modules using the specified extensions. An empty list disables the"
              + " filter, effectively specifying all possible extensions.")
  public ImmutableList<ExtensionArg> extensionFilter;

  @Option(
      name = "extension_info",
      defaultValue = "hidden",
      converter = ExtensionShowConverter.class,
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Specify how much detail about extension usages to include in the query result."
              + " \"Usages\" will only show the extensions names, \"repos\" will also include repos"
              + " imported with use_repo, and \"all\" will also show the other repositories"
              + " generated by extensions.\n")
  public ExtensionShow extensionInfo;

  @Option(
      name = "base_module",
      defaultValue = "<root>",
      converter = ModuleArgConverter.class,
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help = "Specify a module relative to which the specified target repos will be interpreted.")
  public ModuleArg baseModule;

  @Option(
      name = "extension_usages",
      defaultValue = "",
      converter = CommaSeparatedModuleArgListConverter.class,
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Specify modules whose extension usages will be displayed in the show_extension query.")
  public ImmutableList<ModuleArg> extensionUsages;

  @Option(
      name = "depth",
      defaultValue = "-1",
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Maximum display depth of the dependency tree. A depth of 1 displays the direct"
              + " dependencies, for example. For tree, path and all_paths it defaults to"
              + " Integer.MAX_VALUE, while for deps and explain it defaults to 1 (only displays"
              + " direct deps of the root besides the target leaves and their parents).\n")
  public int depth;

  @Option(
      name = "cycles",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Points out dependency cycles inside the displayed tree, which are normally ignored by"
              + " default.")
  public boolean cycles;

  @Option(
      name = "include_builtin",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Include built-in modules in the dependency graph. Disabled by default because it is"
              + " quite noisy.")
  public boolean includeBuiltin;

  @Option(
      name = "charset",
      defaultValue = "utf8",
      converter = CharsetConverter.class,
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Chooses the character set to use for the tree. Only affects text output. Valid values"
              + " are \"utf8\" or \"ascii\". Default is \"utf8\"")
  public Charset charset;

  @Option(
      name = "output",
      defaultValue = "text",
      converter = OutputFormatConverter.class,
      documentationCategory = OptionDocumentationCategory.MOD_COMMAND,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "The format in which the query results should be printed. Allowed values for query are: "
              + "text, json, graph")
  public OutputFormat outputFormat;

  /** Possible subcommands that can be specified for the `mod` command. */
  public enum ModSubcommand {
    DEPS(true),
    GRAPH(true),
    ALL_PATHS(true),
    PATH(true),
    EXPLAIN(true),
    SHOW_REPO(false),
    SHOW_EXTENSION(false),
    DUMP_REPO_MAPPING(false);

    /** Whether this subcommand produces a graph output. */
    private final boolean isGraph;

    ModSubcommand(boolean isGraph) {
      this.isGraph = isGraph;
    }

    @Override
    public String toString() {
      return Ascii.toLowerCase(this.name());
    }

    public boolean isGraph() {
      return isGraph;
    }

    public static String printValues() {
      return "(" + stream(values()).map(ModSubcommand::toString).collect(joining(", ")) + ")";
    }
  }

  /** Converts a subcommand string to a properly typed {@link ModSubcommand} */
  public static class ModSubcommandConverter extends EnumConverter<ModSubcommand> {
    public ModSubcommandConverter() {
      super(ModSubcommand.class, "mod subcommand");
    }
  }

  enum ExtensionShow {
    HIDDEN,
    USAGES,
    REPOS,
    ALL
  }

  /** Converts an option string to a properly typed {@link ExtensionShow} */
  public static class ExtensionShowConverter extends EnumConverter<ExtensionShow> {
    public ExtensionShowConverter() {
      super(ExtensionShow.class, "extension show");
    }
  }

  /** Charset to be used in outputting the `mod` command result. */
  public enum Charset {
    UTF8,
    ASCII
  }

  /** Converts a charset option string to a properly typed {@link Charset} */
  public static class CharsetConverter extends EnumConverter<Charset> {
    public CharsetConverter() {
      super(Charset.class, "output charset");
    }
  }

  /** Possible formats of the `mod` command result. */
  public enum OutputFormat {
    TEXT,
    JSON,
    GRAPH
  }

  /** Converts an output format option string to a properly typed {@link OutputFormat} */
  public static class OutputFormatConverter extends EnumConverter<OutputFormat> {
    public OutputFormatConverter() {
      super(OutputFormat.class, "output format");
    }
  }

  /** Returns a {@link ModOptions} filled with default values for testing. */
  static ModOptions getDefaultOptions() {
    ModOptions options = new ModOptions();
    options.depth = Integer.MAX_VALUE;
    options.cycles = false;
    options.includeUnused = false;
    options.verbose = false;
    options.modulesFrom =
        ImmutableList.of(ModuleArg.SpecificVersionOfModule.create(ModuleKey.ROOT));
    options.charset = Charset.UTF8;
    options.outputFormat = OutputFormat.TEXT;
    options.extensionFilter = null;
    options.extensionInfo = ExtensionShow.HIDDEN;
    return options;
  }
}
