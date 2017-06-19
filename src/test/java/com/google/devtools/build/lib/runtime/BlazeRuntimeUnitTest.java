// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link BlazeRuntime} static methods.
 */
@RunWith(JUnit4.class)
public class BlazeRuntimeUnitTest {
  @Test
  public void requestLogStringParsing() {
    assertThat(BlazeRuntime.getRequestLogString(ImmutableList.of("--client_env=A=B")))
        .isEqualTo("[--client_env=A=B]");
    assertThat(BlazeRuntime.getRequestLogString(ImmutableList.of("--client_env=BROKEN")))
        .isEqualTo("[--client_env=BROKEN]");
    assertThat(BlazeRuntime.getRequestLogString(ImmutableList.of("--client_env=auth=notprinted")))
        .isEqualTo("[--client_env=auth=__private_value_removed__]");
    assertThat(BlazeRuntime.getRequestLogString(ImmutableList.of("--client_env=MY_COOKIE=notprinted")))
        .isEqualTo("[--client_env=MY_COOKIE=__private_value_removed__]");
    assertThat(BlazeRuntime.getRequestLogString(ImmutableList.of("--client_env=dont_paSS_ME=notprinted")))
        .isEqualTo("[--client_env=dont_paSS_ME=__private_value_removed__]");
    assertThat(BlazeRuntime.getRequestLogString(ImmutableList.of("--client_env=ok=COOKIE")))
        .isEqualTo("[--client_env=ok=COOKIE]");
  }
}
