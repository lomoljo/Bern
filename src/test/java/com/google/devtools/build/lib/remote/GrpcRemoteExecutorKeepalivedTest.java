// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.remote.RemoteRetrier.ExponentialBackoff;
import com.google.devtools.build.lib.remote.common.OperationObserver;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.TestUtils;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.common.options.Options;
import com.google.rpc.Code;
import io.grpc.Context;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link GrpcRemoteExecutorKeepalived}.
 */
@RunWith(JUnit4.class)
public class GrpcRemoteExecutorKeepalivedTest {

  private FakeExecutionService executionService;
  private RemoteOptions remoteOptions;
  private Server fakeServer;
  private ListeningScheduledExecutorService retryService;
  private RemoteRetrier retrier;
  private ReferenceCountedChannel channel;
  private Context context;
  private Context prevContext;
  GrpcRemoteExecutorKeepalived executor;

  private static final int MAX_RETRY_ATTEMPTS = 5;

  private static final OutputFile DUMMY_OUTPUT =
      OutputFile.newBuilder()
          .setPath("dummy.txt")
          .setDigest(
              Digest.newBuilder()
                  .setHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                  .setSizeBytes(0)
                  .build())
          .build();

  ExecuteRequest DUMMY_REQUEST = ExecuteRequest.newBuilder()
      .setInstanceName("dummy")
      .setActionDigest(Digest.newBuilder()
          .setHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
          .setSizeBytes(0)
          .build())
      .build();

  ExecuteResponse DUMMY_RESPONSE = ExecuteResponse.newBuilder()
      .setResult(ActionResult.newBuilder().addOutputFiles(DUMMY_OUTPUT).build())
      .build();

  @Before
  public final void setUp() throws Exception {
    executionService = new FakeExecutionService();

    String fakeServerName = "fake server for " + getClass();
    // Use a mutable service registry for later registering the service impl for each test case.
    fakeServer =
        InProcessServerBuilder.forName(fakeServerName)
            .addService(executionService)
            .directExecutor()
            .build()
            .start();

    remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteMaxRetryAttempts = MAX_RETRY_ATTEMPTS;

    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
    retrier =
        TestUtils.newRemoteRetrier(
            () -> new ExponentialBackoff(remoteOptions),
            RemoteRetrier.RETRIABLE_GRPC_EXEC_ERRORS,
            retryService);
    channel =
        new ReferenceCountedChannel(
            InProcessChannelBuilder.forName(fakeServerName)
                .intercept(TracingMetadataUtils.newExecHeadersInterceptor(remoteOptions))
                .directExecutor()
                .build());

    context = TracingMetadataUtils.contextWithMetadata(RequestMetadata.getDefaultInstance());
    prevContext = context.attach();

    executor =
        new GrpcRemoteExecutorKeepalived(
            remoteOptions, channel.retain(), CallCredentialsProvider.NO_CREDENTIALS, retrier);

    channel.release();
  }

  @After
  public void tearDown() throws Exception {
    executor.close();
    context.detach(prevContext);

    retryService.shutdownNow();
    retryService.awaitTermination(
        com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    fakeServer.shutdownNow();
    fakeServer.awaitTermination();
  }


  @Test
  public void executeRemotely_smoke() throws Exception {
    executionService.whenExecute(DUMMY_REQUEST).thenAck().thenAck().thenDone(DUMMY_RESPONSE);

    ExecuteResponse response = executor.executeRemotely(DUMMY_REQUEST, OperationObserver.NO_OP);

    assertThat(response).isEqualTo(DUMMY_RESPONSE);
    assertThat(executionService.getExecTimes()).isEqualTo(1);
  }

  @Test
  public void executeRemotely_retryExecute() throws Exception {
    executionService.whenExecute(DUMMY_REQUEST).thenError(new RuntimeException("Unavailable"));
    executionService.whenExecute(DUMMY_REQUEST).thenError(Code.UNAVAILABLE);
    executionService.whenExecute(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE);

    ExecuteResponse response = executor.executeRemotely(DUMMY_REQUEST, OperationObserver.NO_OP);

    assertThat(executionService.getExecTimes()).isEqualTo(3);
    assertThat(response).isEqualTo(DUMMY_RESPONSE);
  }

  @Test
  public void executeRemotely_retryExecuteAndFail() {
    IOException exception = assertThrows(IOException.class, () -> {
      executor.executeRemotely(DUMMY_REQUEST, OperationObserver.NO_OP);
    });

    assertThat(executionService.getExecTimes()).isEqualTo(MAX_RETRY_ATTEMPTS + 1);
    assertThat(exception.getMessage()).contains("UNAVAILABLE");
  }

  @Test
  public void executeRemotely_executeAndWait() throws Exception {
    executionService.whenExecute(DUMMY_REQUEST).thenAck().thenError(Code.UNAVAILABLE);
    executionService.whenWaitExecution(DUMMY_REQUEST).thenDone(DUMMY_RESPONSE);

    ExecuteResponse response = executor.executeRemotely(DUMMY_REQUEST, OperationObserver.NO_OP);

    assertThat(executionService.getExecTimes()).isEqualTo(1);
    assertThat(executionService.getWaitTimes()).isEqualTo(1);
    assertThat(response).isEqualTo(DUMMY_RESPONSE);
  }

  @Test
  public void executeRemotely_executeAndRetryWait() throws Exception {
    executionService.whenExecute(DUMMY_REQUEST).thenAck().thenError(Code.UNAVAILABLE);
    executionService.whenWaitExecution(DUMMY_REQUEST).thenDone(DUMMY_RESPONSE);

    ExecuteResponse response = executor.executeRemotely(DUMMY_REQUEST, OperationObserver.NO_OP);

    assertThat(executionService.getExecTimes()).isEqualTo(1);
    assertThat(executionService.getWaitTimes()).isEqualTo(1);
    assertThat(response).isEqualTo(DUMMY_RESPONSE);
  }

  @Test
  public void executeRemotely_executeAndRetryWait_forever() throws Exception {
    executionService.whenExecute(DUMMY_REQUEST).thenAck().thenError(Code.UNAVAILABLE);
    int errorTimes = MAX_RETRY_ATTEMPTS + 2;
    for (int i = 0; i < errorTimes; ++i) {
      executionService.whenWaitExecution(DUMMY_REQUEST).thenAck().thenError(Code.DEADLINE_EXCEEDED);
    }
    executionService.whenWaitExecution(DUMMY_REQUEST).thenDone(DUMMY_RESPONSE);

    ExecuteResponse response = executor.executeRemotely(DUMMY_REQUEST, OperationObserver.NO_OP);

    assertThat(executionService.getExecTimes()).isEqualTo(1);
    assertThat(executionService.getWaitTimes()).isEqualTo(errorTimes + 1);
    assertThat(response).isEqualTo(DUMMY_RESPONSE);
  }
}
