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
#include "common.h"

#include <iostream>

#include "nlohmann/json.hpp"
using json = nlohmann::json;

ModuleDep parse_ddi(std::istream &ddi_stream) {
  ModuleDep dep{};
  json data = json::parse(ddi_stream);
  auto &rules = data["rules"];
  if (rules.size() > 1) {
    std::cerr << "bad ddi" << std::endl;
    std::exit(1);
  }
  if (rules.empty()) {
    return dep;
  }
  auto &provides = rules[0]["provides"];
  if (provides.size() > 1) {
    std::cerr << "bad ddi" << std::endl;
    std::exit(1);
  }
  if (provides.size() == 1) {
    std::string name = provides[0]["logical-name"];
    dep.gen_bmi = true;
    dep.name = name;
  }
  for (const auto &item : rules[0]["requires"]) {
    dep.require_list.push_back(item["logical-name"]);
  }
  return dep;
}
Cpp20ModulesInfo parse_info(std::istream &info_stream) {
  json data = json::parse(info_stream);
  Cpp20ModulesInfo info;
  for (const auto &item : data["modules"].items()) {
    info.modules[item.key()] = item.value();
  }
  for (const auto &item : data["usages"].items()) {
    info.usages[item.key()] = item.value();
  }
  return info;
}
