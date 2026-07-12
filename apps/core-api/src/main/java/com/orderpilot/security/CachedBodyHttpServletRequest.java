package com.orderpilot.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Bounded request-body cache for gateway signature verification so controllers still receive the
 * exact bytes after HMAC/content-hash checks. Oversized bodies fail closed (null wrap result).
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
  private final byte[] cachedBody;

  CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
    super(request);
    this.cachedBody = cachedBody;
  }

  static CachedBodyHttpServletRequest wrap(HttpServletRequest request, int maxBytes)
      throws IOException {
    if (maxBytes <= 0) {
      return null;
    }
    String contentLengthHeader = request.getHeader("Content-Length");
    if (contentLengthHeader != null && !contentLengthHeader.isBlank()) {
      if (!contentLengthHeader.matches("^[0-9]{1,12}$")) {
        return null;
      }
      long declared = Long.parseLong(contentLengthHeader);
      if (declared > maxBytes) {
        return null;
      }
    }
    byte[] buffered = request.getInputStream().readNBytes(maxBytes + 1);
    if (buffered.length > maxBytes) {
      return null;
    }
    return new CachedBodyHttpServletRequest(request, buffered);
  }

  byte[] cachedBody() {
    return cachedBody;
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream input = new ByteArrayInputStream(cachedBody);
    return new ServletInputStream() {
      @Override
      public boolean isFinished() {
        return input.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        // unused for cached body
      }

      @Override
      public int read() {
        return input.read();
      }
    };
  }

  @Override
  public BufferedReader getReader() {
    Charset charset = resolveCharset();
    return new BufferedReader(new InputStreamReader(getInputStream(), charset));
  }

  private Charset resolveCharset() {
    String encoding = getCharacterEncoding();
    if (encoding == null || encoding.isBlank()) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(encoding);
    } catch (Exception ex) {
      return StandardCharsets.UTF_8;
    }
  }
}
