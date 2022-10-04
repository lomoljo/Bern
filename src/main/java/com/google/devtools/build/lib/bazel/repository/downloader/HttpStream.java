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

package com.google.devtools.build.lib.bazel.repository.downloader;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.bazel.repository.downloader.RetryingInputStream.Reconnector;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.util.zip.GZIPInputStream;
import javax.annotation.WillCloseWhenClosed;

/**
 * Input stream that validates checksum resumes downloads on error.
 *
 * <p>This class is not thread safe, but it is safe to message pass its objects between threads.
 */
// TODO(yannic): Delete this class.
@ThreadCompatible
final class HttpStream extends FilterInputStream {

  static final int PRECHECK_BYTES = 32 * 1024;
  private static final int GZIP_BUFFER_BYTES = 8192;  // same as ByteStreams#copy
  private static final ImmutableSet<String> GZIPPED_EXTENSIONS = ImmutableSet.of("gz", "tgz");
  private static final ImmutableSet<String> GZIP_CONTENT_ENCODING =
      ImmutableSet.of("gzip", "x-gzip");

  /** Factory for {@link HttpStream}. */
  @ThreadSafe
  static class Factory {

    private final ProgressInputStream.Factory progressInputStreamFactory;

    Factory(ProgressInputStream.Factory progressInputStreamFactory) {
      this.progressInputStreamFactory = progressInputStreamFactory;
    }

    @VisibleForTesting
    HttpStream create(
        @WillCloseWhenClosed URLConnection connection,
        URI originalUri,
        Optional<Checksum> checksum,
        Reconnector reconnector)
        throws IOException {
      return create(connection, originalUri, checksum, reconnector, Optional.<String>absent());
    }

    @SuppressWarnings("resource")
    HttpStream create(
        @WillCloseWhenClosed URLConnection connection,
        URI originalUri,
        Optional<Checksum> checksum,
        Reconnector reconnector,
        Optional<String> type)
        throws IOException {
      InputStream stream = new InterruptibleInputStream(connection.getInputStream());
      try {
        // If server supports range requests, we can retry on read errors. See RFC7233 § 2.3.
        RetryingInputStream retrier = null;
        if (Iterables.contains(
                Splitter.on(',')
                    .trimResults()
                    .split(Strings.nullToEmpty(connection.getHeaderField("Accept-Ranges"))),
                "bytes")) {
          retrier = new RetryingInputStream(stream, reconnector);
          stream = retrier;
        }

        try {
          String contentLength = connection.getHeaderField("Content-Length");
          if (contentLength != null) {
            long expectedSize = Long.parseLong(contentLength);
            stream = new CheckContentLengthInputStream(stream, expectedSize);
          }
        } catch (NumberFormatException ignored) {
          // ignored
        }

        stream = progressInputStreamFactory.create(stream, connection.getURL().toURI(), originalUri);

        // Determine if we need to transparently gunzip. See RFC2616 § 3.5 and § 14.11. Please note
        // that some web servers will send Content-Encoding: gzip even when we didn't request it if
        // the file is a .gz file. Therefore we take the type parameter from the rule http_archive
        // in consideration. If the repository/file that we are downloading is already compressed we
        // should not decompress it to preserve the desired file format.
        if (GZIP_CONTENT_ENCODING.contains(Strings.nullToEmpty(connection.getContentEncoding()))
            && !GZIPPED_EXTENSIONS.contains(HttpUtils.getExtension(connection.getURL().getPath()))
            && !GZIPPED_EXTENSIONS.contains(HttpUtils.getExtension(originalUri.getPath()))
            && !typeIsGZIP(type)) {
          stream = new GZIPInputStream(stream, GZIP_BUFFER_BYTES);
        }

        if (checksum.isPresent()) {
          stream = new HashInputStream(stream, checksum.get());
          byte[] buffer = new byte[PRECHECK_BYTES];
          int read = 0;
          while (read < PRECHECK_BYTES) {
            int amount;
            amount = stream.read(buffer, read, PRECHECK_BYTES - read);
            if (amount == -1) {
              break;
            }
            read += amount;
          }
          if (read < PRECHECK_BYTES) {
            stream.close();
            stream = ByteStreams.limit(new ByteArrayInputStream(buffer), read);
          } else {
            stream = new SequenceInputStream(new ByteArrayInputStream(buffer), stream);
          }
        }
      } catch (URISyntaxException e) {
        throw new IllegalStateException(e);
      } catch (Exception e) {
        try {
          stream.close();
        } catch (IOException e2) {
          e.addSuppressed(e2);
        }
        throw e;
      }
      return new HttpStream(stream);
    }

    /**
     * Checks if the given type is GZIP
     *
     * @param type extension, e.g. "tar.gz"
     * @return whether the type is GZIP or not
     */
    private static boolean typeIsGZIP(Optional<String> type) {
      if (type.isPresent()) {
        String t = type.get();

        if (t.contains(".")) {
          // We only want to look at the last extension.
          t = HttpUtils.getExtension(t);
        }

        return GZIPPED_EXTENSIONS.contains(t);
      }
      return false;
    }
  }

  HttpStream(@WillCloseWhenClosed InputStream delegate) {
    super(delegate);
  }
}
