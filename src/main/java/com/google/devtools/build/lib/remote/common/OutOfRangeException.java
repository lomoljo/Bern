package com.google.devtools.build.lib.remote.common;

import java.io.IOException;

/**
 * An exception to indicate the digest size exceeds the accepted limit set by the remote-cache.
 */
public final class OutOfRangeException extends IOException {

  public OutOfRangeException(String resourceName) {
    super(String.format("Resource %s size exceeds the limit set by remote-cache.", resourceName));
  }
}
