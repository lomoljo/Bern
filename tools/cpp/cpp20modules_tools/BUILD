# Copyright 2018 The Bazel Authors. All rights reserved.
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

filegroup(
    name = "srcs",
    srcs = glob(
        ["**"],
    ),
    visibility = ["//tools/cpp:__pkg__"],
)

cc_library(
    name = "common",
    srcs = ["common/common.cc"],
    hdrs = ["common/common.h"],
    includes = ["."],
    deps = [
        "@nlohmann_json//:json",
    ],
)

cc_library(
    name = "agg-ddi-lib",
    srcs = ["agg-ddi/agg-ddi.cc"],
    hdrs = ["agg-ddi/agg-ddi.h"],
    deps = [":common"],
)

cc_binary(
    name = "agg-ddi",
    srcs = ["agg-ddi/main.cc"],
    deps = [
        ":agg-ddi-lib",
    ],
)

cc_library(
    name = "gen-modmap-lib",
    srcs = ["gen-modmap/gen-modmap.cc"],
    hdrs = ["gen-modmap/gen-modmap.h"],
    deps = [":common"],
)

cc_binary(
    name = "gen-modmap",
    srcs = ["gen-modmap/main.cc"],
    deps = [":gen-modmap-lib"],
)

filegroup(
    name = "embedded_tools",
    srcs = [
        "BUILD.tools",
        ":agg-ddi",
        ":gen-modmap",
    ],
    visibility = ["//tools/cpp:__pkg__"],
)

cc_test(
    name = "gen-modmap_test",
    srcs = ["gen-modmap/gen-modmap_test.cc"],
    deps = [
        ":gen-modmap-lib",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_test(
    name = "agg-ddi_test",
    srcs = ["agg-ddi/agg-ddi_test.cc"],
    deps = [
        ":agg-ddi-lib",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_test(
    name = "common_test",
    srcs = ["common/common_test.cc"],
    deps = [
        ":common",
        "@com_google_googletest//:gtest_main",
    ],
)
