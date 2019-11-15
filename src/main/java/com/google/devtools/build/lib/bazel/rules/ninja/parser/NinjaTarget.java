// Copyright 2019 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.rules.ninja.parser;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.annotation.concurrent.Immutable;

/**
 * Ninja target (build statement) representation.
 */
@Immutable
public final class NinjaTarget {
  private final String ruleName;
  private final ImmutableSortedKeyListMultimap<InputKind, PathFragment> inputs;
  private final ImmutableSortedKeyListMultimap<OutputKind, PathFragment> outputs;
  private final ImmutableSortedMap<String, String> variables;

  public NinjaTarget(String ruleName,
      ImmutableSortedKeyListMultimap<InputKind, PathFragment> inputs,
      ImmutableSortedKeyListMultimap<OutputKind, PathFragment> outputs,
      ImmutableSortedMap<String, String> variables) {
    this.ruleName = ruleName;
    this.inputs = inputs;
    this.outputs = outputs;
    this.variables = variables;
  }

  public String getRuleName() {
    return ruleName;
  }

  public ImmutableSortedMap<String, String> getVariables() {
    return variables;
  }

  public boolean hasInputs() {
    return !inputs.isEmpty();
  }

  public List<PathFragment> getOutputs() {
    return outputs.get(OutputKind.USUAL);
  }

  public List<PathFragment> getImplicitOutputs() {
    return outputs.get(OutputKind.IMPLICIT);
  }

  public Collection<PathFragment> getAllOutputs() {
    return outputs.values();
  }

  public Collection<PathFragment> getAllInputs() {
    return inputs.values();
  }

  public Collection<PathFragment> getUsualInputs() {
    return inputs.get(InputKind.USUAL);
  }

  public Collection<PathFragment> getImplicitInputs() {
    return inputs.get(InputKind.IMPLICIT);
  }

  public Collection<PathFragment> getOrderOnlyInputs() {
    return inputs.get(InputKind.ORDER_ONLY);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    NinjaTarget that = (NinjaTarget) o;
    return Objects.equals(ruleName, that.ruleName) &&
        Objects.equals(inputs, that.inputs) &&
        Objects.equals(outputs, that.outputs) &&
        Objects.equals(variables, that.variables);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ruleName, inputs, outputs, variables);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link NinjaTarget}. */
  public static class Builder {
    private String ruleName;
    private final ImmutableSortedKeyListMultimap.Builder<InputKind, PathFragment> inputsBuilder;
    private final ImmutableSortedKeyListMultimap.Builder<OutputKind, PathFragment> outputsBuilder;

    private final ImmutableSortedMap.Builder<String, String> variablesBuilder;

    private Builder() {
      inputsBuilder = ImmutableSortedKeyListMultimap.builder();
      outputsBuilder = ImmutableSortedKeyListMultimap.builder();
      variablesBuilder = ImmutableSortedMap.naturalOrder();
    }

    public Builder setRuleName(String ruleName) {
      this.ruleName = ruleName;
      return this;
    }

    public Builder addInputs(InputKind kind, Collection<PathFragment> inputs) {
      inputsBuilder.putAll(kind, inputs);
      return this;
    }

    public Builder addOutputs(OutputKind kind, Collection<PathFragment> outputs) {
      outputsBuilder.putAll(kind, outputs);
      return this;
    }

    public Builder addVariable(String key, String value) {
      variablesBuilder.put(key, value);
      return this;
    }

    public NinjaTarget build() {
      Preconditions.checkNotNull(ruleName);
      return new NinjaTarget(ruleName,
          inputsBuilder.build(),
          outputsBuilder.build(),
          variablesBuilder.build());
    }
  }

  /**
   * Enum with possible kinds of inputs.
   */
  @Immutable
  public enum InputKind implements InputOutputKind {
    USUAL, IMPLICIT, ORDER_ONLY
  }

  /**
   * Enum with possible kinds of outputs.
   */
  @Immutable
  public enum OutputKind implements InputOutputKind {
    USUAL, IMPLICIT
  }

  /**
   * Marker interface, so that it is possible to address {@link InputKind} and {@link OutputKind}
   * together in one map.
   */
  @Immutable
  public interface InputOutputKind {}
}
