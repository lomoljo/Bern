// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.android.desugar;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.lang.reflect.Modifier.isFinal;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.android.desugar.testdata.CaptureLambda;
import com.google.devtools.build.android.desugar.testdata.ConcreteFunction;
import com.google.devtools.build.android.desugar.testdata.ConstructorReference;
import com.google.devtools.build.android.desugar.testdata.GuavaLambda;
import com.google.devtools.build.android.desugar.testdata.InnerClassLambda;
import com.google.devtools.build.android.desugar.testdata.InterfaceWithLambda;
import com.google.devtools.build.android.desugar.testdata.Lambda;
import com.google.devtools.build.android.desugar.testdata.LambdaInOverride;
import com.google.devtools.build.android.desugar.testdata.MethodReference;
import com.google.devtools.build.android.desugar.testdata.MethodReferenceInSubclass;
import com.google.devtools.build.android.desugar.testdata.MethodReferenceSuperclass;
import com.google.devtools.build.android.desugar.testdata.OuterReferenceLambda;
import com.google.devtools.build.android.desugar.testdata.SpecializedFunction;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test that exercises classes in the {@code testdata} package. This is meant to be run against a
 * desugared version of those classes, which in turn exercise various desugaring features.
 */
@RunWith(JUnit4.class)
public class DesugarFunctionalTest {

  private final int expectedBridgesFromSameTarget;
  private final int expectedBridgesFromSeparateTarget;
  private final boolean expectLambdaMethodsInInterfaces;

  public DesugarFunctionalTest() {
    this(3, 1, false);
  }

  /** Constructor for testing desugar while allowing default and static interface methods. */
  protected DesugarFunctionalTest(
      boolean expectBridgesFromSeparateTarget, boolean expectDefaultMethods) {
    this(
        expectDefaultMethods ? 0 : 3,
        expectBridgesFromSeparateTarget ? 1 : 0,
        expectDefaultMethods);
  }

  private DesugarFunctionalTest(
      int bridgesFromSameTarget, int bridgesFromSeparateTarget, boolean lambdaMethodsInInterfaces) {
    this.expectedBridgesFromSameTarget = bridgesFromSameTarget;
    this.expectedBridgesFromSeparateTarget = bridgesFromSeparateTarget;
    this.expectLambdaMethodsInInterfaces = lambdaMethodsInInterfaces;
  }

  @Test
  public void testGuavaLambda() {
    GuavaLambda lambdaUse = new GuavaLambda(ImmutableList.of("Sergey", "Larry", "Alex"));
    assertThat(lambdaUse.as()).containsExactly("Alex");
  }

  @Test
  public void testJavaLambda() {
    Lambda lambdaUse = new Lambda(ImmutableList.of("Sergey", "Larry", "Alex"));
    assertThat(lambdaUse.as()).containsExactly("Alex");
  }

  @Test
  public void testLambdaForIntersectionType() throws Exception {
    assertThat(Lambda.hello().call()).isEqualTo("hello");
  }

  @Test
  public void testCapturingLambda() {
    CaptureLambda lambdaUse = new CaptureLambda(ImmutableList.of("Sergey", "Larry", "Alex"));
    assertThat(lambdaUse.prefixed("L")).containsExactly("Larry");
  }

  @Test
  public void testOuterReferenceLambda() throws Exception {
    OuterReferenceLambda lambdaUse = new OuterReferenceLambda(ImmutableList.of("Sergey", "Larry"));
    assertThat(lambdaUse.filter(ImmutableList.of("Larry", "Alex"))).containsExactly("Larry");
    assertThat(
            isFinal(
                OuterReferenceLambda.class
                    .getDeclaredMethod("lambda$filter$0$OuterReferenceLambda", String.class)
                    .getModifiers()))
        .isTrue();
  }

  /**
   * Tests a lambda in a subclass whose generated lambda$ method has the same name and signature as
   * a lambda$ method generated by Javac in a superclass and both of these methods are used in the
   * implementation of the subclass (by calling super). Naively this leads to wrong behavior (in
   * this case, return a non-empty list) because the lambda$ in the superclass is never used once
   * its made non-private during desugaring.
   */
  @Test
  public void testOuterReferenceLambdaInOverride() throws Exception {
    OuterReferenceLambda lambdaUse = new LambdaInOverride(ImmutableList.of("Sergey", "Larry"));
    assertThat(lambdaUse.filter(ImmutableList.of("Larry", "Alex"))).isEmpty();
    assertThat(
            isFinal(
                LambdaInOverride.class
                    .getDeclaredMethod("lambda$filter$0$LambdaInOverride", String.class)
                    .getModifiers()))
        .isTrue();
  }

  @Test
  public void testLambdaInAnonymousClassReferencesSurroundingMethodParameter() throws Exception {
    assertThat(Lambda.mult(21).apply(2).call()).isEqualTo(42);
  }

  /** Tests a lambda that accesses a method parameter across 2 nested anonymous classes. */
  @Test
  public void testLambdaInNestedAnonymousClass() throws Exception {
    InnerClassLambda lambdaUse = new InnerClassLambda(ImmutableList.of("Sergey", "Larry"));
    assertThat(lambdaUse.prefixFilter("L").apply(ImmutableList.of("Lois", "Larry")).call())
        .containsExactly("Larry");
  }

  @Test
  public void testClassMethodReference() {
    MethodReference methodrefUse = new MethodReference(ImmutableList.of("Sergey", "Larry", "Alex"));
    StringBuilder dest = new StringBuilder();
    methodrefUse.appendAll(dest);
    assertThat(dest.toString()).isEqualTo("SergeyLarryAlex");
  }

  // Regression test for b/33378312
  @Test
  public void testHiddenMethodReference() {
    MethodReference methodrefUse = new MethodReference(ImmutableList.of("Sergey", "Larry", "Alex"));
    assertThat(methodrefUse.intersect(ImmutableList.of("Alex", "Sundar"))).containsExactly("Alex");
  }

  // Regression test for b/33378312
  @Test
  public void testHiddenStaticMethodReference() {
    MethodReference methodrefUse =
        new MethodReference(ImmutableList.of("Sergey", "Larry", "Sundar"));
    assertThat(methodrefUse.some()).containsExactly("Sergey", "Sundar");
  }

  @Test
  public void testDuplicateHiddenMethodReference() {
    MethodReference methodrefUse = new MethodReference(ImmutableList.of("Sergey", "Larry", "Alex"));
    assertThat(methodrefUse.onlyIn(ImmutableList.of("Alex", "Sundar"))).containsExactly("Sundar");
  }

  // Regression test for b/36201257
  @Test
  public void testMethodReferenceThatNeedsBridgeInSubclass() {
    MethodReferenceInSubclass methodrefUse =
        new MethodReferenceInSubclass(ImmutableList.of("Sergey", "Larry", "Alex"));
    assertThat(methodrefUse.containsE()).containsExactly("Sergey", "Alex");
    assertThat(methodrefUse.startsWithL()).containsExactly("Larry");
    // Check to make sure sub- and superclass have bridge methods with matching descriptors but
    // different names
    Method superclassBridge = findOnlyBridge(MethodReferenceSuperclass.class);
    Method subclassBridge = findOnlyBridge(MethodReferenceInSubclass.class);
    assertThat(superclassBridge.getName()).isNotEqualTo(subclassBridge.getName());
    assertThat(superclassBridge.getParameterTypes()).isEqualTo(subclassBridge.getParameterTypes());
  }

  private Method findOnlyBridge(Class<?> clazz) {
    Method result = null;
    for (Method m : clazz.getDeclaredMethods()) {
      if (m.getName().startsWith("bridge$")) {
        assertWithMessage(m.getName()).that(result).isNull();
        result = m;
      }
    }
    assertWithMessage(clazz.getSimpleName()).that(result).isNotNull();
    return result;
  }

  // Regression test for b/33378312
  @Test
  public void testThrowingPrivateMethodReference() throws Exception {
    MethodReference methodrefUse = new MethodReference(ImmutableList.of("Sergey", "Larry"));
    Callable<?> stringer = methodrefUse.stringer();
    try {
      stringer.call();
      fail("IOException expected");
    } catch (IOException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("SergeyLarry");
    } catch (Exception e) {
      throw e;
    }
  }

  // Regression test for b/33304582
  @Test
  public void testInterfaceMethodReference() {
    MethodReference methodrefUse = new MethodReference(ImmutableList.of("Sergey", "Larry", "Alex"));
    MethodReference.Transformer<String> transform =
        new MethodReference.Transformer<String>() {
          @Override
          public String transform(String input) {
            return input.substring(1);
          }
        };
    assertThat(methodrefUse.transform(transform)).containsExactly("ergey", "arry", "lex");
  }

  @Test
  public void testConstructorReference() {
    ConstructorReference initRefUse = new ConstructorReference(ImmutableList.of("1", "2", "42"));
    assertThat(initRefUse.toInt()).containsExactly(1, 2, 42);
  }

  // Regression test for b/33304582
  @Test
  public void testPrivateConstructorReference() {
    ConstructorReference initRefUse = ConstructorReference.singleton().apply("17");
    assertThat(initRefUse.toInt()).containsExactly(17);
  }

  // This test is similar to testPrivateConstructorReference but the private constructor of an inner
  // class is used as a method reference.  That causes Javac to generate a bridge constructor and
  // a lambda body method that calls it, so the desugaring step doesn't need to do anything to make
  // the private constructor visible.  This is mostly to double-check that we don't interfere with
  // this "already-working" scenario.
  @Test
  public void testPrivateConstructorAccessedThroughJavacGeneratedBridge() {
    @SuppressWarnings("ReturnValueIgnored")
    RuntimeException expected =
        assertThrows(
            RuntimeException.class,
            () -> ConstructorReference.emptyThroughJavacGeneratedBridge().get());
    assertThat(expected).hasMessageThat().isEqualTo("got it!");
  }

  @Test
  public void testExpressionMethodReference() {
    assertThat(
            MethodReference.stringChars(new StringBuilder().append("Larry").append("Sergey"))
                .apply(5))
        .isEqualTo('S');
  }

  @Test
  public void testFieldMethodReference() {
    MethodReference methodrefUse = new MethodReference(ImmutableList.of("Sergey", "Larry", "Alex"));
    assertThat(methodrefUse.toPredicate().test("Larry")).isTrue();
    assertThat(methodrefUse.toPredicate().test("Sundar")).isFalse();
  }

  @Test
  public void testConcreteFunctionWithInheritedBridgeMethods() {
    assertThat(new ConcreteFunction().apply("1234567890987654321")).isEqualTo(1234567890987654321L);
    assertThat(ConcreteFunction.parseAll(ImmutableList.of("5", "17"), new ConcreteFunction()))
        .containsExactly(5L, 17L);
  }

  @Test
  public void testLambdaWithInheritedBridgeMethods() throws Exception {
    assertThat(ConcreteFunction.toInt().apply("123456789")).isEqualTo(123456789);
    assertThat(ConcreteFunction.parseAll(ImmutableList.of("5", "17"), ConcreteFunction.toInt()))
        .containsExactly(5, 17);
    // Expect String apply(Number) and any expected bridges
    assertThat(ConcreteFunction.toInt().getClass().getDeclaredMethods())
        .hasLength(expectedBridgesFromSameTarget + 1);
    // Check that we only copied over methods, no fields, from the functional interface
    assertThrows(
        NoSuchFieldException.class,
        () ->
            ConcreteFunction.toInt()
                .getClass()
                .getDeclaredField("DO_NOT_COPY_INTO_LAMBDA_CLASSES"));
    assertThat(SpecializedFunction.class.getDeclaredField("DO_NOT_COPY_INTO_LAMBDA_CLASSES"))
        .isNotNull();
  }

  /** Tests lambdas with bridge methods when the implemented interface is in a separate target. */
  @Test
  public void testLambdaWithBridgeMethodsForInterfaceInSeparateTarget() {
    assertThat(ConcreteFunction.isInt().test(123456789L)).isTrue();
    assertThat(
            ConcreteFunction.doFilter(
                ImmutableList.of(123456789L, 1234567890987654321L), ConcreteFunction.isInt()))
        .containsExactly(123456789L);
    // Expect test(Number) and any expected bridges
    assertThat(ConcreteFunction.isInt().getClass().getDeclaredMethods())
        .hasLength(expectedBridgesFromSeparateTarget + 1);
  }

  @Test
  public void testLambdaInInterfaceStaticInitializer() {
    assertThat(InterfaceWithLambda.DIGITS).containsExactly("0", "1").inOrder();
    // <clinit> doesn't count but if there's a lambda method then Jacoco adds more methods
    assertThat(InterfaceWithLambda.class.getDeclaredMethods().length != 0)
        .isEqualTo(expectLambdaMethodsInInterfaces);
  }

  /** Checks that the resource file included in the original Jar is still there unchanged. */
  @Test
  public void testResourcePreserved() throws Exception {
    try (InputStream content = Lambda.class.getResource("testresource.txt").openStream()) {
      assertThat(new String(ByteStreams.toByteArray(content), UTF_8)).isEqualTo("test");
    }
  }

  /**
   * Test for b/62456849. After desugar, the method {@code lambda$mult$0} should still be in the
   * class.
   */
  @Test
  public void testCallMethodWithLambdaNamingConvention()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Method method = Lambda.class.getDeclaredMethod("lambda$mult$0");
    Object value = method.invoke(null);
    assertThat(value).isInstanceOf(Integer.class);
    assertThat((Integer) value).isEqualTo(0);
  }
}
