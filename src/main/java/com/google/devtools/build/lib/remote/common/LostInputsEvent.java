// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote.common;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.vfs.PathFragment;

/** An event that is sent when inputs to an action are lost from remote server. */
public class LostInputsEvent implements Postable {
  private final ImmutableList<PathFragment> lostInputs;

  public LostInputsEvent(ImmutableList<PathFragment> lostInputs) {
    this.lostInputs = lostInputs;
  }

  public ImmutableList<PathFragment> getLostInputs() {
    return lostInputs;
  }
}
