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

// The "srcs_for_embedded_tools" rule in the same package sets the line below to
// include runfiles.h from the correct path. Do not modify the line below.
#include <fstream>
#include <iostream>
#include <optional>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "agg-ddi.h"

#include "nlohmann/json.hpp"
using json = nlohmann::json;

void write_output(std::ostream &output, const Cpp20ModulesInfo &info) {
  json data;
  data["modules"] = info.modules;
  data["usages"] = info.usages;
  output << data.dump(4);
}
