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
package com.google.devtools.build.lib.vfs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** This class implements the FileSystem interface using direct calls to the UNIX filesystem. */
@ThreadSafe
public abstract class AbstractFileSystem extends FileSystem {

  protected static final String ERR_PERMISSION_DENIED = " (Permission denied)";
  protected static final Profiler profiler = Profiler.instance();
  /**
   * Even though Bazel attempts to force the default charset to be ISO-8859-1, which makes String
   * identical to the "bag of raw bytes" that a UNIX path is, the JVM may still use a different
   * encoding for file paths, e.g. on macOS (always UTF-8). When interoperating with the filesystem
   * through Java APIs, we thus need to reencode paths to the JVM's native filesystem encoding.
   */
  private static final Charset JAVA_PATH_CHARSET =
      Charset.forName(System.getProperty("sun.jnu.encoding"), ISO_8859_1);

  private static final Set<Charset> ASCII_INCOMPATIBLE_CHARSETS =
      ImmutableSet.of(
          UTF_16,
          StandardCharsets.UTF_16BE,
          StandardCharsets.UTF_16LE,
          Charset.forName("UTF-32"),
          Charset.forName("UTF-32BE"),
          Charset.forName("UTF-32LE"));

  public AbstractFileSystem(DigestHashFunction digestFunction) {
    super(digestFunction);
  }

  @Override
  protected InputStream getInputStream(PathFragment path) throws IOException {
    // This loop is a workaround for an apparent bug in FileInputStream.open, which delegates
    // ultimately to JVM_Open in the Hotspot JVM.  This call is not EINTR-safe, so we must do the
    // retry here.
    for (; ; ) {
      try {
        return createMaybeProfiledInputStream(path);
      } catch (FileNotFoundException e) {
        if (e.getMessage().endsWith("(Interrupted system call)")) {
          continue;
        } else {
          // FileInputStream throws FileNotFoundException if opening fails for any reason,
          // including permissions. Fix it up here.
          // TODO(tjgq): Migrate to java.nio.
          if (e.getMessage().equals(path + ERR_PERMISSION_DENIED)) {
            throw new FileAccessException(e.getMessage());
          }
          throw e;
        }
      }
    }
  }

  @Override
  public String getJavaPathString(PathFragment path) {
    System.err.println("JAVA_PATH_CHARSET: " + JAVA_PATH_CHARSET);
    return toJavaIoString(path.getPathString());
  }

  /**
   * Reencodes a Bazel internal path string into the equivalent representation for Java (N)IO
   * methods, if necessary.
   */
  protected String toJavaIoString(String s) {
    return canSkipReencoding(s) ? s : new String(s.getBytes(ISO_8859_1), JAVA_PATH_CHARSET);
  }

  /**
   * Reencodes a path string obtained from Java (N)IO methods into Bazel's internal string
   * representation, if necessary.
   */
  protected String fromJavaIoString(String s) {
    return canSkipReencoding(s) ? s : new String(s.getBytes(JAVA_PATH_CHARSET), ISO_8859_1);
  }

  private boolean canSkipReencoding(String s) {
    // Most common charsets other than UTF-16 and UTF-32 are compatible with ASCII and most paths
    // are ASCII, so avoid any conversion if possible.
    return JAVA_PATH_CHARSET == ISO_8859_1
        || JAVA_PATH_CHARSET == US_ASCII
        || (!ASCII_INCOMPATIBLE_CHARSETS.contains(JAVA_PATH_CHARSET)
            && StringUnsafe.getInstance().isAscii(s));
  }

  /** Allows the mapping of PathFragment to InputStream to be overridden in subclasses. */
  protected InputStream createFileInputStream(PathFragment path) throws IOException {
    return new FileInputStream(getJavaPathString(path));
  }

  /** Returns either normal or profiled FileInputStream. */
  private InputStream createMaybeProfiledInputStream(PathFragment path) throws IOException {
    final String name = path.toString();
    if (profiler.isActive()
        && (profiler.isProfiling(ProfilerTask.VFS_READ)
            || profiler.isProfiling(ProfilerTask.VFS_OPEN))) {
      long startTime = Profiler.nanoTimeMaybe();
      try {
        // Replace default FileInputStream instance with the custom one that does profiling.
        return new ProfiledInputStream(createFileInputStream(path), name);
      } finally {
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_OPEN, name);
      }
    } else {
      // Use normal FileInputStream instance if profiler is not enabled.
      return createFileInputStream(path);
    }
  }

  private static final ImmutableSet<StandardOpenOption> READ_WRITE_BYTE_CHANNEL_OPEN_OPTIONS =
      Sets.immutableEnumSet(READ, WRITE, CREATE, TRUNCATE_EXISTING);

  @Override
  protected SeekableByteChannel createReadWriteByteChannel(PathFragment path) throws IOException {
    String name = path.getPathString();

    boolean shouldProfile = profiler.isActive() && profiler.isProfiling(ProfilerTask.VFS_OPEN);

    long startTime = Profiler.nanoTimeMaybe();

    try {
      // Currently, we do not proxy SeekableByteChannel for profiling reads and writes.
      return Files.newByteChannel(
          Paths.get(getJavaPathString(path)), READ_WRITE_BYTE_CHANNEL_OPEN_OPTIONS);
    } finally {
      if (shouldProfile) {
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_OPEN, name);
      }
    }
  }

  /**
   * Returns either normal or profiled FileOutputStream. Should be used by subclasses to create
   * default OutputStream instance.
   */
  protected OutputStream createFileOutputStream(PathFragment path, boolean append, boolean internal)
      throws FileNotFoundException {
    final String name = path.toString();
    if (!internal
        && profiler.isActive()
        && (profiler.isProfiling(ProfilerTask.VFS_WRITE)
            || profiler.isProfiling(ProfilerTask.VFS_OPEN))) {
      long startTime = Profiler.nanoTimeMaybe();
      try {
        return new ProfiledFileOutputStream(getJavaPathString(path), append);
      } finally {
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_OPEN, name);
      }
    } else {
      return new FileOutputStream(getJavaPathString(path), append);
    }
  }

  @Override
  protected OutputStream getOutputStream(PathFragment path, boolean append, boolean internal)
      throws IOException {
    try {
      return createFileOutputStream(path, append, internal);
    } catch (FileNotFoundException e) {
      // FileOutputStream throws FileNotFoundException if opening fails for any reason,
      // including permissions. Fix it up here.
      // TODO(tjgq): Migrate to java.nio.
      if (e.getMessage().equals(path + ERR_PERMISSION_DENIED)) {
        throw new FileAccessException(e.getMessage());
      }
      throw e;
    }
  }

  private static final class ProfiledInputStream extends FilterInputStream {
    private final InputStream impl;
    private final String name;

    public ProfiledInputStream(InputStream impl, String name) {
      super(impl);
      this.impl = impl;
      this.name = name;
    }

    @Override
    public int read() throws IOException {
      long startTime = Profiler.nanoTimeMaybe();
      try {
        return impl.read();
      } finally {
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_READ, name);
      }
    }

    @Override
    public int read(byte[] b) throws IOException {
      return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      long startTime = Profiler.nanoTimeMaybe();
      try {
        return impl.read(b, off, len);
      } finally {
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_READ, name);
      }
    }
  }

  private static final class ProfiledFileOutputStream extends FileOutputStream {
    private final String name;

    public ProfiledFileOutputStream(String name, boolean append) throws FileNotFoundException {
      super(name, append);
      this.name = name;
    }

    @Override
    public void write(byte[] b) throws IOException {
      write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      long startTime = Profiler.nanoTimeMaybe();
      try {
        super.write(b, off, len);
      } finally {
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_WRITE, name);
      }
    }
  }
}
