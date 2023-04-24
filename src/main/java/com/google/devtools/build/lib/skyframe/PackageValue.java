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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.CPUHeavySkyKey;
import com.google.devtools.build.skyframe.NotComparableSkyValue;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Collection;

/** A Skyframe value representing a package. */
@AutoCodec(explicitlyAllowClass = Package.class)
@Immutable
@ThreadSafe
public class PackageValue implements NotComparableSkyValue {
  private final Package pkg;

  public PackageValue(Package pkg) {
    this.pkg = Preconditions.checkNotNull(pkg);
  }

  /**
   * Returns the package. This package may contain errors, in which case the caller should throw a
   * {@link com.google.devtools.build.lib.packages.BuildFileContainsErrorsException} if an
   * error-free package is needed. See also {@link PackageErrorFunction} for the case where
   * encountering a package with errors should shut down the build but the caller can handle
   * packages with errors.
   */
  public Package getPackage() {
    return pkg;
  }

  @Override
  public String toString() {
    return "<PackageValue name=" + pkg.getName() + ">";
  }

  public static Key key(PackageIdentifier pkgIdentifier) {
    return Key.create(pkgIdentifier);
  }

  /** Skyframe key for packages */
  @AutoCodec.VisibleForSerialization
  @AutoCodec
  public static class Key extends AbstractSkyKey<PackageIdentifier> implements CPUHeavySkyKey {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    private Key(PackageIdentifier arg) {
      super(arg);
    }

    @AutoCodec.VisibleForSerialization
    @AutoCodec.Instantiator
    static Key create(PackageIdentifier arg) {
      return interner.intern(new Key(arg));
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.PACKAGE;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  public static ImmutableList<SkyKey> keys(Iterable<PackageIdentifier> pkgIdentifiers) {
    ImmutableList.Builder<SkyKey> keys =
        (pkgIdentifiers instanceof Collection)
            ? ImmutableList.builderWithExpectedSize(((Collection) pkgIdentifiers).size())
            : ImmutableList.builder();
    for (PackageIdentifier pkgIdentifier : pkgIdentifiers) {
      keys.add(key(pkgIdentifier));
    }
    return keys.build();
  }
}
