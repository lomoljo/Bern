// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.http;

import static com.google.common.base.Preconditions.checkState;

import com.google.auth.Credentials;
import com.google.common.collect.ImmutableList;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.internal.StringUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ChannelHandler for downloads. */
final class HttpDownloadHandler extends AbstractHttpHandler<HttpObject> {

  private OutputStream out;
  private boolean keepAlive = HttpVersion.HTTP_1_1.isKeepAliveDefault();
  private boolean downloadSucceeded;
  private HttpResponse response;

  private long bytesReceived;
  private long contentLength = -1;
  /** the path header in the http request */
  private String path;
  /** the offset at which to download */
  private long offset;
  /** the bytes to skip in a full or chunked response */
  private OptionalInt skipBytes;

  public HttpDownloadHandler(
      Credentials credentials, ImmutableList<Entry<String, String>> extraHttpHeaders) {
    super(credentials, extraHttpHeaders);
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
    if (!msg.decoderResult().isSuccess()) {
      failAndClose(new IOException("Failed to parse the HTTP response."), ctx);
      return;
    }
    if (!(msg instanceof HttpResponse) && !(msg instanceof HttpContent)) {
      failAndClose(
          new IllegalArgumentException(
              "Unsupported message type: " + StringUtil.simpleClassName(msg)),
          ctx);
      return;
    }
    checkState(userPromise != null, "response before request");

    if (msg instanceof HttpResponse) {
      response = (HttpResponse) msg;
      if (!response.protocolVersion().equals(HttpVersion.HTTP_1_1)) {
        HttpException error =
            new HttpException(
                response, "HTTP version 1.1 is required, was: " + response.protocolVersion(), null);
        failAndClose(error, ctx);
        return;
      }
      boolean contentLengthSet = HttpUtil.isContentLengthSet(response);
      if (!contentLengthSet && !HttpUtil.isTransferEncodingChunked(response)) {
        HttpException error =
            new HttpException(
                response, "Missing 'Content-Length' or 'Transfer-Encoding: chunked' header", null);
        failAndClose(error, ctx);
        return;
      }

      if (contentLengthSet) {
        contentLength = HttpUtil.getContentLength(response);
      }
      boolean full_content = response.status().equals(HttpResponseStatus.OK);
      boolean partial_content = response.status().equals(HttpResponseStatus.PARTIAL_CONTENT);
      if (full_content) {
        if (offset != 0) {
          // We requested a range but the server replied with a full response.
          // We need to skip `offset` bytes of the response.
          if (!skipBytes.isPresent()) {
            // This is the first chunk, or the full response.
            skipBytes = OptionalInt.of((int)offset);
          }
        }
      } else if (partial_content) {
        Optional<HttpException> error = validateContentRangeHeader(response.headers());
        if (error.isPresent()) {
          failAndClose(error.get(), ctx);
          return;
        }
      }
      downloadSucceeded = full_content || partial_content;
      if (!downloadSucceeded) {
        out = new ByteArrayOutputStream();
      }
      keepAlive = HttpUtil.isKeepAlive((HttpResponse) msg);
    }

    if (msg instanceof HttpContent) {
      checkState(response != null, "content before headers");

      ByteBuf content = ((HttpContent) msg).content();
      int readableBytes = content.readableBytes();
      if (skipBytes.isPresent() && skipBytes.getAsInt() > 0) {
        int skipNow = skipBytes.getAsInt();
        if (skipNow >= readableBytes) {
          skipNow = readableBytes;
        }
        content.readerIndex(content.readerIndex() + skipNow);
        skipBytes = OptionalInt.of(skipBytes.getAsInt() - skipNow);
        readableBytes = readableBytes - skipNow;
      }
      content.readBytes(out, readableBytes);
      bytesReceived += readableBytes;
      if (msg instanceof LastHttpContent) {
        if (downloadSucceeded) {
          succeedAndReset(ctx);
        } else {
          String errorMsg = response.status() + "\n";
          errorMsg +=
              new String(
                  ((ByteArrayOutputStream) out).toByteArray(), HttpUtil.getCharset(response));
          out.close();
          HttpException error = new HttpException(response, errorMsg, null);
          failAndReset(error, ctx);
        }
      }
    }
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    checkState(userPromise == null, "handler can't be shared between pipelines.");
    userPromise = promise;
    if (!(msg instanceof DownloadCommand)) {
      failAndResetUserPromise(
          new IllegalArgumentException(
              "Unsupported message type: " + StringUtil.simpleClassName(msg)));
      return;
    }
    DownloadCommand cmd = (DownloadCommand) msg;
    out = cmd.out();
    path = constructPath(cmd.uri(), cmd.digest().getHash(), cmd.casDownload());
    offset = cmd.offset();
    skipBytes = OptionalInt.empty();
    HttpRequest request = buildRequest(path, constructHost(cmd.uri()), cmd.offset());
    addCredentialHeaders(request, cmd.uri());
    addExtraRemoteHeaders(request);
    addUserAgentHeader(request);
    ctx.writeAndFlush(request)
        .addListener(
            (f) -> {
              if (!f.isSuccess()) {
                failAndClose(f.cause(), ctx);
              }
            });
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
    if (t instanceof ReadTimeoutException) {
      super.exceptionCaught(ctx, new DownloadTimeoutException(path, bytesReceived, contentLength));
    } else {
      super.exceptionCaught(ctx, t);
    }
  }

  private HttpRequest buildRequest(String path, String host, long offset) {
    HttpRequest httpRequest =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    httpRequest.headers().set(HttpHeaderNames.HOST, host);
    httpRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    httpRequest.headers().set(HttpHeaderNames.ACCEPT, "*/*");
    httpRequest.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
    if (offset != 0) {
      httpRequest.headers().set(HttpHeaderNames.RANGE, String.format("%s=%d-", HttpHeaderValues.BYTES, offset));
    }
    return httpRequest;
  }

  private Optional<HttpException> validateContentRangeHeader(HttpHeaders headers) {
    if (!headers.contains(HttpHeaderNames.CONTENT_RANGE)) {
      return Optional.of(new HttpException(response, "Missing 'Content-Range' header", null));
    }
    Pattern pattern = Pattern.compile("bytes\\s+(?<start>[0-9]+)-(?<end>[0-9]+)/(?<size>[0-9]*|\\*)");
    Matcher matcher = pattern.matcher(response.headers().get(HttpHeaderNames.CONTENT_RANGE));
    if (!matcher.matches()) {
      return Optional.of(new HttpException(response, "Unexpected 'Content-Range' header", null));
    }
    long start = Long.valueOf(matcher.group("start"));
    if (start != offset) {
      return Optional.of(new HttpException(
              response, String.format("Unexpected 'Content-Range' start: Expected %d but got %d", offset, start), null));
    }
    return Optional.empty();
  }

  private void succeedAndReset(ChannelHandlerContext ctx) {
    // All resets must happen *before* completing the user promise. Otherwise there is a race
    // condition, where this handler can be reused even though it is closed. In addition, if reset
    // calls ctx.close(), then that triggers a call to AbstractHttpHandler.channelInactive, which
    // attempts to close the user promise.
    ChannelPromise promise = userPromise;
    userPromise = null;
    try {
      reset(ctx);
    } finally {
      promise.setSuccess();
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void failAndClose(Throwable t, ChannelHandlerContext ctx) {
    ChannelPromise promise = userPromise;
    userPromise = null;
    try {
      ctx.close();
    } finally {
      promise.setFailure(t);
    }
  }

  private void failAndReset(Throwable t, ChannelHandlerContext ctx) {
    ChannelPromise promise = userPromise;
    userPromise = null;
    try {
      reset(ctx);
    } finally {
      promise.setFailure(t);
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void reset(ChannelHandlerContext ctx) {
    try {
      if (!keepAlive) {
        ctx.close();
      }
    } finally {
      out = null;
      keepAlive = HttpVersion.HTTP_1_1.isKeepAliveDefault();
      downloadSucceeded = false;
      response = null;
    }
  }
}
