// Copyright 2024 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/** Represent the parsed VENDOR.bazel file */
@AutoValue
public abstract class VendorFileValue implements SkyValue {

  @SerializationConstant public static final SkyKey KEY = () -> SkyFunctions.VENDOR_FILE;

  public abstract ImmutableList<RepositoryName> getIgnoredRepos();

  public abstract ImmutableList<RepositoryName> getPinnedRepos();

  public static VendorFileValue create(
      ImmutableList<RepositoryName> ignoredRepos, ImmutableList<RepositoryName> pinnedRepos) {
    return new AutoValue_VendorFileValue(ignoredRepos, pinnedRepos);
  }
}
