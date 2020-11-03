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
package com.google.devtools.build.lib.remote;

import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionBlockingStub;
import build.bazel.remote.execution.v2.WaitExecutionRequest;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteRetrier.ProgressiveBackoff;
import com.google.devtools.build.lib.remote.common.OperationObserver;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.longrunning.Operation;
import com.google.longrunning.Operation.ResultCase;
import com.google.rpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * A remote work executor that uses gRPC for communicating the work, inputs and outputs.
 *
 * <p>It differs from {@link GrpcRemoteExecutor} by setting timeout on each execution calls to
 * ensure we never be stuck due to network issues.
 *
 * @see <a href="https://docs.google.com/document/d/1NgDPsCIwprDdqC1zj0qQrh5KGK2hQTSTux1DAvi4rSc">
 * Keepalived Remote Execution</a>
 */
@ThreadSafe
public class GrpcRemoteExecutorKeepalived implements RemoteExecutionClient {

  private final RemoteOptions remoteOptions;
  private final ReferenceCountedChannel channel;
  private final CallCredentialsProvider callCredentialsProvider;
  private final RemoteRetrier retrier;

  private final AtomicBoolean closed = new AtomicBoolean();

  public GrpcRemoteExecutorKeepalived(
      RemoteOptions remoteOptions,
      ReferenceCountedChannel channel,
      CallCredentialsProvider callCredentialsProvider,
      RemoteRetrier retrier) {
    this.remoteOptions = remoteOptions;
    this.channel = channel;
    this.callCredentialsProvider = callCredentialsProvider;
    this.retrier = retrier;
  }

  private ExecutionBlockingStub executionBlockingStub() {
    return ExecutionGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(callCredentialsProvider.getCallCredentials())
        .withDeadlineAfter(remoteOptions.remoteTimeout.getSeconds(), TimeUnit.SECONDS);
  }

  private static class Execution {
    private final ExecuteRequest request;
    private final OperationObserver observer;
    private final RemoteRetrier retrier;
    private final CallCredentialsProvider callCredentialsProvider;
    private final ProgressiveBackoff backoff;
    private final Supplier<ExecutionBlockingStub> executionBlockingStubSupplier;

    // Last response (without error) we received from server.
    private Operation lastOperation;

    Execution(ExecuteRequest request,
        OperationObserver observer,
        RemoteRetrier retrier,
        CallCredentialsProvider callCredentialsProvider,
        Supplier<ExecutionBlockingStub> executionBlockingStubSupplier) {
      this.request = request;
      this.observer = observer;
      this.retrier = retrier;
      this.callCredentialsProvider = callCredentialsProvider;
      this.backoff = new ProgressiveBackoff(this.retrier::newBackoff);
      this.executionBlockingStubSupplier = executionBlockingStubSupplier;
    }

    ExecuteResponse start() throws IOException, InterruptedException {
      // Execute has two components: the Execute call and (optionally) the WaitExecution call.
      // This is the simple flow without any errors:
      //
      // - A call to Execute returns streamed updates on an Operation object.
      // - We wait until the Operation is finished.
      //
      // Error possibilities:
      // - An Execute call may fail with a retriable error (raise a StatusRuntimeException).
      //   - If the failure occurred before the first Operation is returned and tells us the
      //     execution is accepted, we retry the call.
      //   - Otherwise, we call WaitExecution on the Operation.
      // - A WaitExecution call may fail with a retriable error (raise a StatusRuntimeException).
      //   In that case, we retry the WaitExecution call on the same operation object.
      // - A WaitExecution call may fail with a NOT_FOUND error (raise a StatusRuntimeException).
      //   That means the Operation was lost on the server, and we will retry to Execute.
      // - Any call can return an Operation object with an error status in the result. Such
      //   Operations are completed and failed; however, some of these errors may be retriable.
      //   These errors should trigger a retry of the Execute call, resulting in a new Operation.
      Preconditions.checkState(lastOperation == null);

      ExecuteResponse response = null;
      // Exit the loop as long as we get a response from either Execute() or WaitExecution().
      while (response == null) {
        // We use refreshIfUnauthenticated inside retry block. If use it outside, retrier will stop
        // retrying when received a unauthenticated error, and propagate to refreshIfUnauthenticated
        // which will then call retrier again. It will reset the retry time counter so we could
        // retry more than --remote_retry times which is not expected.
        response = retrier
            .execute(() -> Utils.refreshIfUnauthenticated(this::execute, callCredentialsProvider),
                backoff);

        // If no response from Execute(), use WaitExecution() in a "loop" which is implicated inside
        // the retry block.
        if (response == null) {
          response = retrier.execute(
              () -> Utils.refreshIfUnauthenticated(this::waitExecution, callCredentialsProvider),
              backoff);
        }
      }

      return response;
    }

    @Nullable
    ExecuteResponse execute() throws IOException {
      Preconditions.checkState(lastOperation == null);

      try {
        Iterator<Operation> streamOperations = executionBlockingStubSupplier.get().execute(request);

        // We don't want to reset backoff for Execute() since if there is an error:
        //   1. If happened before we received a first response, we want to ensure the retry counter
        //      is increased and call Execute() again.
        //   2. Otherwise, we will fallback to WaitExecution() loop.
        return handleStreamOperations(streamOperations, /* resetBackoff */ false);
      } catch (StatusRuntimeException e) {
        if (lastOperation != null) {
          // By returning null, we are going to fallback to WaitExecution() loop.
          return null;
        }
        throw new IOException(e);
      }
    }

    @Nullable
    ExecuteResponse waitExecution() throws IOException {
      Preconditions.checkState(lastOperation != null);

      WaitExecutionRequest request = WaitExecutionRequest.newBuilder()
          .setName(lastOperation.getName())
          .build();
      try {
        Iterator<Operation> streamOperations = executionBlockingStubSupplier.get()
            .waitExecution(request);
        // We want to reset backoff for WaitExecution() so we can "infinitely" wait for the
        // execution to complete as long as they are making progress (by returning response).
        return handleStreamOperations(streamOperations, /* resetBackoff */ true);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Code.NOT_FOUND) {
          // Operation was lost on the server. Retry Execute.
          lastOperation = null;
          return null;
        }
        throw new IOException(e);
      }
    }

    /**
     * Process a stream of operations from Execute() or WaitExecution().
     *
     * @param resetBackoff reset backoff if true once received a response successfully.
     */
    ExecuteResponse handleStreamOperations(Iterator<Operation> streamOperations,
        boolean resetBackoff) throws IOException {
      try {
        while (streamOperations.hasNext()) {
          Operation operation = streamOperations.next();

          if (resetBackoff) {
            // Assuming the server has made progress since we received the response. Reset the
            // backoff so that this request has a full deck of retries.
            backoff.reset();
          }

          ExecuteResponse response = handleOperation(operation, observer);

          // At this point, we received the response without error
          lastOperation = operation;

          if (response != null) {
            return response;
          }
        }

        // The operation completed successfully but without a result.
        throw new IOException("Remote server error: execution terminated with no result.");
      } finally {
        close(streamOperations);
      }
    }

    void close(Iterator<Operation> streamOperations) {
      // The blocking streaming call closes correctly only when trailers and a Status are received
      // from the server so that onClose() is called on this call's CallListener. Under normal
      // circumstances (no cancel/errors), these are guaranteed to be sent by the server only if
      // streamOperations.hasNext() has been called after all replies from the stream have been
      // consumed.
      try {
        while (streamOperations.hasNext()) {
          streamOperations.next();
        }
      } catch (StatusRuntimeException e) {
        // Cleanup: ignore exceptions, because the meaningful errors have already been propagated.
      }
    }

    static void throwIfError(Status status, @Nullable ExecuteResponse resp) {
      if (status.getCode() == Code.OK.value()) {
        return;
      }
      throw new ExecutionStatusException(status, resp);
    }

    @Nullable
    static ExecuteResponse handleOperation(Operation operation, OperationObserver observer)
        throws IOException {
      // Update execution progress to the caller.
      //
      // After called `execute` above, the action is actually waiting for an available gRPC
      // connection to be sent. Once we get a reply from server, we know the connection is up and
      // indicate to the caller the fact by forwarding the `operation`.
      //
      // The accurate execution status of the action relies on the server
      // implementation:
      //   1. Server can reply the accurate status in `operation.metadata.stage`;
      //   2. Server may send a reply without metadata. In this case, we assume the action is
      //      accepted by the server and will be executed ASAP;
      //   3. Server may execute the action silently and send a reply once it is done.
      observer.onNext(operation);

      if (operation.getResultCase() == Operation.ResultCase.ERROR) {
        throwIfError(operation.getError(), null);
      }

      if (operation.getDone()) {
        if (operation.getResultCase() == ResultCase.RESULT_NOT_SET) {
          throw new ExecutionStatusException(Status.newBuilder()
              .setCode(com.google.rpc.Code.DATA_LOSS_VALUE)
              .setMessage("Unexpected result of remote execution: no result")
              .build(), null);
        }
        Preconditions.checkState(operation.getResultCase() != Operation.ResultCase.RESULT_NOT_SET);
        ExecuteResponse response = operation.getResponse().unpack(ExecuteResponse.class);
        if (response.hasStatus()) {
          throwIfError(response.getStatus(), response);
        }
        if (!response.hasResult()) {
          throw new ExecutionStatusException(Status.newBuilder()
              .setCode(com.google.rpc.Code.DATA_LOSS_VALUE)
              .setMessage("Unexpected result of remote execution: no result")
              .build(), response);
        }
        return response;
      }

      return null;
    }
  }

  @Override
  public ExecuteResponse executeRemotely(ExecuteRequest request, OperationObserver observer)
      throws IOException, InterruptedException {
    Execution execution = new Execution(request, observer, retrier, callCredentialsProvider,
        this::executionBlockingStub);
    return execution.start();
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    channel.release();
  }
}
