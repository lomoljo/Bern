# Copyright 2016 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Repository rule to generate host xcode_config and xcode_version targets.

   The xcode_config and xcode_version targets are configured for xcodes/SDKs
   installed on the local host.
"""

OSX_EXECUTE_TIMEOUT = 600

def _parse_platforms(xcodebuild_result, developer_dir):
    if (xcodebuild_result.return_code != 0):
        error_msg = (
            "Invoking xcodebuild failed, developer dir: {devdir}, " +
            "return code {code}, stderr: {err}, stdout: {out}"
        ).format(
            devdir = developer_dir,
            code = xcodebuild_result.return_code,
            err = xcodebuild_result.stderr,
            out = xcodebuild_result.stdout,
        )
        return None, error_msg

    platforms_array = json.decode(xcodebuild_result.stdout, default = [])
    if not platforms_array:
        error_msg =(
            "Malformed json output from xcodebuild, developer dir: {devdir}, " +
            "stdout: {out}"
        ).format(
            devdir = developer_dir,
            out = xcodebuild_result.stdout,
        )
        return None, error_msg

    platforms = {}
    for platform in platforms_array:
        platform_name = platform.get("platform")
        if not platform_name:
            error_msg =(
                "Malformed json output from xcodebuild - " +
                "missing 'platform' key, developer dir: {devdir}, stdout: {out}"
            ).format(
                devdir = developer_dir,
                out = xcodebuild_result.stdout,
            )
            return None, error_msg

        sdk_version = platform.get("sdkVersion")
        if not sdk_version:
            error_msg =(
                "Malformed json output from xcodebuild - " +
                "missing 'sdkVersion' key, developer dir: {devdir}, stdout: {out}"
            ).format(
                devdir = developer_dir,
                out = xcodebuild_result.stdout,
            )
            return None, error_msg

        sdk_path = platform.get("sdkPath")
        if not sdk_path:
            error_msg =(
                "Malformed json output from xcodebuild - " +
                "missing 'sdkPath' key, developer dir: {devdir}, stdout: {out}"
            ).format(
                devdir = developer_dir,
                out = xcodebuild_result.stdout,
            )
            return None, error_msg

        platforms[platform_name] = struct(
            sdkVersion = sdk_version,
            sdkPath = sdk_path,
        )

    return platforms, ""

def _search_string(fullstring, prefix, suffix):
    """Returns the substring between two given substrings of a larger string.

    Args:
      fullstring: The larger string to search.
      prefix: The substring that should occur directly before the returned string.
      suffix: The substring that should occur directly after the returned string.
    Returns:
      A string occurring in fullstring exactly prefixed by prefix, and exactly
      terminated by suffix. For example, ("hello goodbye", "lo ", " bye") will
      return "good". If there is no such string, returns the empty string.
    """

    prefix_index = fullstring.find(prefix)
    if (prefix_index < 0):
        return ""
    result_start_index = prefix_index + len(prefix)
    suffix_index = fullstring.find(suffix, result_start_index)
    if (suffix_index < 0):
        return ""
    return fullstring[result_start_index:suffix_index]

def _sdk_info(repository_ctx, platforms, platform_name):
    """Returns the SDK info for the given platform name."""
    platform = platforms.get(platform_name)
    if not platform:
        return None

    sdk_settings_path = platform.sdkPath + "/SDKSettings.json"

    minimum_os = None
    sdk_settings = json.decode(repository_ctx.read(sdk_settings_path), default = None)
    if not sdk_settings:
        error_msg = (
            "Malformed SDKSettings.json file for {platform_name} at {path}"
        ).format(
            platform_name = platform_name,
            path = sdk_settings_path,
        )
        return struct(
            error_msg = error_msg,
            minimum_os = None,
            version = platform.sdkVersion,
        )

    supported_targets = sdk_settings.get("SupportedTargets")
    if not supported_targets:
        error_msg = (
            "Malformed SDKSettings.json file for {platform_name} at {path} - " +
            "Missing 'SupportedTargets' key"
        ).format(
            platform_name = platform_name,
            path = sdk_settings_path,
        )
        return struct(
            error_msg = error_msg,
            minimum_os = None,
            version = platform.sdkVersion,
        )

    platform_settings = supported_targets.get(platform_name)
    if not platform_settings:
        error_msg = (
            "Malformed SDKSettings.json file for {platform_name} at {path} - " +
            "Missing 'SupportedTargets.{platform_name}' key"
        ).format(
            platform_name = platform_name,
            path = sdk_settings_path,
        )
        return struct(
            error_msg = error_msg,
            minimum_os = None,
            version = platform.sdkVersion,
        )

    minimum_os = platform_settings.get("MinimumDeploymentTarget")
    if not minimum_os:
        error_msg = (
            "Malformed SDKSettings.json file for {platform_name} at {path} - " +
            "Missing 'SupportedTargets.{platform_name}.MinimumDeploymentTarget' key"
        ).format(
            platform_name = platform_name,
            path = sdk_settings_path,
        )
        return struct(
            error_msg = error_msg,
            minimum_os = None,
            version = platform.sdkVersion,
        )

    return struct(
        error_msg = None,
        minimum_os = minimum_os,
        version = platform.sdkVersion,
    )

def _xcode_version_output(repository_ctx, name, version, aliases, developer_dir, timeout):
    """Returns a string containing an xcode_version build target."""
    build_contents = ""
    decorated_aliases = []

    for alias in aliases:
        decorated_aliases.append("'%s'" % alias)

    repository_ctx.report_progress("Fetching SDK information for Xcode %s" % version)
    xcodebuild_result = repository_ctx.execute(
        ["xcrun", "xcodebuild", "-version", "-sdk", "-json"],
        timeout,
        {"DEVELOPER_DIR": developer_dir},
    )
    platforms, error_msg = _parse_platforms(xcodebuild_result, developer_dir)

    error_msgs = []
    if error_msg:
        error_msgs.append(error_msg)

    ios_sdk = _sdk_info(repository_ctx, platforms, "iphoneos")
    tvos_sdk = _sdk_info(repository_ctx, platforms, "appletvos")
    macos_sdk = _sdk_info(repository_ctx, platforms, "macosx")
    visionos_sdk = _sdk_info(repository_ctx, platforms, "xros")
    watchos_sdk = _sdk_info(repository_ctx, platforms, "watchos")

    build_contents += "xcode_version(\n  name = '%s'," % name
    build_contents += "\n  version = '%s'," % version
    if aliases:
        build_contents += "\n  aliases = [%s]," % ", ".join(decorated_aliases)
    if ios_sdk:
        build_contents += "\n  default_ios_sdk_version = '%s'," % ios_sdk.version
        if ios_sdk.minimum_os:
            build_contents += "\n  ios_sdk_minimum_os = '%s'," % ios_sdk.minimum_os
        if ios_sdk.error_msg:
            error_msgs.append(ios_sdk.error_msg)
    if tvos_sdk:
        build_contents += "\n  default_tvos_sdk_version = '%s'," % tvos_sdk.version
        if tvos_sdk.minimum_os:
            build_contents += "\n  tvos_sdk_minimum_os = '%s'," % tvos_sdk.minimum_os
        if tvos_sdk.error_msg:
            error_msgs.append(tvos_sdk.error_msg)
    if macos_sdk:
        build_contents += "\n  default_macos_sdk_version = '%s'," % macos_sdk.version
        if macos_sdk.minimum_os:
            build_contents += "\n  macos_sdk_minimum_os = '%s'," % macos_sdk.minimum_os
        if macos_sdk.error_msg:
            error_msgs.append(macos_sdk.error_msg)
    if visionos_sdk:
        build_contents += "\n  default_visionos_sdk_version = '%s'," % visionos_sdk.version
        if visionos_sdk.minimum_os:
            build_contents += "\n  visionos_sdk_minimum_os = '%s'," % visionos_sdk.minimum_os
        if visionos_sdk.error_msg:
            error_msgs.append(visionos_sdk.error_msg)
    if watchos_sdk:
        build_contents += "\n  default_watchos_sdk_version = '%s'," % watchos_sdk.version
        if watchos_sdk.minimum_os:
            build_contents += "\n  watchos_sdk_minimum_os = '%s'," % watchos_sdk.minimum_os
        if watchos_sdk.error_msg:
            error_msgs.append(watchos_sdk.error_msg)
    build_contents += "\n)\n"
    if error_msgs:
        error_msg = "\n".join(error_msgs)
        build_contents += "\n# Error: " + error_msg.replace("\n", "\n# Error: ") + "\n\n"
        print(error_msg)

    return build_contents

VERSION_CONFIG_STUB = "xcode_config(name = 'host_xcodes')"

def run_xcode_locator(repository_ctx, xcode_locator_src_label):
    """Generates xcode-locator from source and runs it.

    Builds xcode-locator in the current repository directory.
    Returns the standard output of running xcode-locator with -v, which will
    return information about locally installed Xcode toolchains and the versions
    they are associated with.

    This should only be invoked on a darwin OS, as xcode-locator cannot be built
    otherwise.

    Args:
      repository_ctx: The repository context.
      xcode_locator_src_label: The label of the source file for xcode-locator.
    Returns:
      A 2-tuple containing:
      output: A list representing installed xcode toolchain information. Each
          element of the list is a struct containing information for one installed
          toolchain. This is an empty list if there was an error building or
          running xcode-locator.
      err: An error string describing the error that occurred when attempting
          to build and run xcode-locator, or None if the run was successful.
    """
    repository_ctx.report_progress("Building xcode-locator")
    xcodeloc_src_path = str(repository_ctx.path(xcode_locator_src_label))
    env = repository_ctx.os.environ
    if "BAZEL_OSX_EXECUTE_TIMEOUT" in env:
        timeout = int(env["BAZEL_OSX_EXECUTE_TIMEOUT"])
    else:
        timeout = OSX_EXECUTE_TIMEOUT

    xcrun_result = repository_ctx.execute([
        "env",
        "-i",
        "DEVELOPER_DIR={}".format(env.get("DEVELOPER_DIR", default = "")),
        "xcrun",
        "--sdk",
        "macosx",
        "clang",
        "-mmacosx-version-min=10.13",
        "-fobjc-arc",
        "-framework",
        "CoreServices",
        "-framework",
        "Foundation",
        "-o",
        "xcode-locator-bin",
        xcodeloc_src_path,
    ], timeout)

    if (xcrun_result.return_code != 0):
        suggestion = ""
        if "Agreeing to the Xcode/iOS license" in xcrun_result.stderr:
            suggestion = ("(You may need to sign the Xcode license." +
                          " Try running 'sudo xcodebuild -license')")
        error_msg = (
            "Generating xcode-locator-bin failed. {suggestion} " +
            "return code {code}, stderr: {err}, stdout: {out}"
        ).format(
            suggestion = suggestion,
            code = xcrun_result.return_code,
            err = xcrun_result.stderr,
            out = xcrun_result.stdout,
        )
        return ([], error_msg.replace("\n", " "))

    repository_ctx.report_progress("Running xcode-locator")
    xcode_locator_result = repository_ctx.execute(
        ["./xcode-locator-bin", "-v"],
        timeout,
    )
    if (xcode_locator_result.return_code != 0):
        error_msg = (
            "Invoking xcode-locator failed, " +
            "return code {code}, stderr: {err}, stdout: {out}"
        ).format(
            code = xcode_locator_result.return_code,
            err = xcode_locator_result.stderr,
            out = xcode_locator_result.stdout,
        )
        return ([], error_msg.replace("\n", " "))
    xcode_toolchains = []

    # xcode_dump is comprised of newlines with different installed Xcode versions,
    # each line of the form <version>:<comma_separated_aliases>:<developer_dir>.
    xcode_dump = xcode_locator_result.stdout
    for xcodeversion in xcode_dump.split("\n"):
        if ":" in xcodeversion:
            infosplit = xcodeversion.split(":")
            toolchain = struct(
                version = infosplit[0],
                aliases = infosplit[1].split(","),
                developer_dir = infosplit[2],
            )
            xcode_toolchains.append(toolchain)
    return (xcode_toolchains, None)

def _darwin_build_file(repository_ctx):
    """Evaluates local system state to create xcode_config and xcode_version targets."""
    repository_ctx.report_progress("Fetching the default Xcode version")
    env = repository_ctx.os.environ

    if "BAZEL_OSX_EXECUTE_TIMEOUT" in env:
        timeout = int(env["BAZEL_OSX_EXECUTE_TIMEOUT"])
    else:
        timeout = OSX_EXECUTE_TIMEOUT

    xcodebuild_result = repository_ctx.execute([
        "env",
        "-i",
        "DEVELOPER_DIR={}".format(env.get("DEVELOPER_DIR", default = "")),
        "xcrun",
        "xcodebuild",
        "-version",
    ], timeout)

    (toolchains, xcodeloc_err) = run_xcode_locator(
        repository_ctx,
        Label(repository_ctx.attr.xcode_locator),
    )

    if xcodeloc_err:
        return VERSION_CONFIG_STUB + "\n# Error: " + xcodeloc_err + "\n"

    default_xcode_version = ""
    default_xcode_build_version = ""
    if xcodebuild_result.return_code == 0:
        default_xcode_version = _search_string(xcodebuild_result.stdout, "Xcode ", "\n")
        default_xcode_build_version = _search_string(
            xcodebuild_result.stdout,
            "Build version ",
            "\n",
        )
    default_xcode_target = ""
    target_names = []
    buildcontents = ""

    for toolchain in toolchains:
        version = toolchain.version
        aliases = toolchain.aliases
        developer_dir = toolchain.developer_dir
        target_name = "version%s" % version.replace(".", "_")
        buildcontents += _xcode_version_output(
            repository_ctx,
            target_name,
            version,
            aliases,
            developer_dir,
            timeout,
        )
        target_label = "':%s'" % target_name
        target_names.append(target_label)
        if (version.startswith(default_xcode_version) and
            version.endswith(default_xcode_build_version)):
            default_xcode_target = target_label
    buildcontents += "xcode_config(\n  name = 'host_xcodes',"
    if target_names:
        buildcontents += "\n  versions = [%s]," % ", ".join(target_names)
    if not default_xcode_target and target_names:
        default_xcode_target = sorted(target_names, reverse = True)[0]
        print("No default Xcode version is set with 'xcode-select'; picking %s" %
              default_xcode_target)
    if default_xcode_target:
        buildcontents += "\n  default = %s," % default_xcode_target

    buildcontents += "\n)\n"
    buildcontents += "available_xcodes(\n  name = 'host_available_xcodes',"
    if target_names:
        buildcontents += "\n  versions = [%s]," % ", ".join(target_names)
    if default_xcode_target:
        buildcontents += "\n  default = %s," % default_xcode_target
    buildcontents += "\n)\n"
    if repository_ctx.attr.remote_xcode:
        buildcontents += "xcode_config(name = 'all_xcodes',"
        buildcontents += "\n  remote_versions = '%s', " % repository_ctx.attr.remote_xcode
        buildcontents += "\n  local_versions = ':host_available_xcodes', "
        buildcontents += "\n)\n"
    return buildcontents

def _impl(repository_ctx):
    """Implementation for the local_config_xcode repository rule.

    Generates a BUILD file containing a root xcode_config target named 'host_xcodes',
    which points to an xcode_version target for each version of Xcode installed on
    the local host machine. If no versions of Xcode are present on the machine
    (for instance, if this is a non-darwin OS), creates a stub target.

    Args:
      repository_ctx: The repository context.
    """

    os_name = repository_ctx.os.name
    build_contents = "package(default_visibility = ['//visibility:public'])\n\n"
    if (os_name.startswith("mac os")):
        build_contents += _darwin_build_file(repository_ctx)
    else:
        build_contents += VERSION_CONFIG_STUB
    repository_ctx.file("BUILD", build_contents)

xcode_autoconf = repository_rule(
    environ = [
        "DEVELOPER_DIR",
        "XCODE_VERSION",
    ],
    implementation = _impl,
    configure = True,
    attrs = {
        "xcode_locator": attr.string(),
        "remote_xcode": attr.string(),
    },
)

def xcode_configure(xcode_locator_label, remote_xcode_label = None):
    """Generates a repository containing host Xcode version information."""
    xcode_autoconf(
        name = "local_config_xcode",
        xcode_locator = xcode_locator_label,
        remote_xcode = remote_xcode_label,
    )

xcode_configure_extension = module_extension(
    implementation = lambda ctx: xcode_configure("@bazel_tools//tools/osx:xcode_locator.m"),
)
