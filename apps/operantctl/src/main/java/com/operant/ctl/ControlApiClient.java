package com.operant.ctl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Bounded HTTP client for the Core control-plane read surface. The command-to-path map is a fixed
 * allowlist: there is no way to request an arbitrary path, method, or body, and every request is
 * signed with the control-plane key.
 *
 * <p>Runtime bound: a single configured operation deadline ({@code config.timeoutSeconds()}) is an
 * absolute total budget for connect + TLS handshake + response headers + complete bounded body read.
 * The body is consumed by a custom size-capping {@link java.net.http.HttpResponse.BodySubscriber}
 * that runs <em>inside</em> the JDK {@link HttpClient} exchange, so {@link HttpRequest#timeout()}
 * covers the full exchange. There is no separate, unbounded synchronous {@link InputStream} read:
 * a server that sends headers and a partial body and then stalls cannot block the CLI past the
 * deadline. Response size is enforced while the client consumes the body, never after reading an
 * unbounded response into memory.
 */
final class ControlApiClient {
  static final String STATUS_PATH = "/api/v1/internal/control/status";
  static final String HEALTH_PATH = "/api/v1/internal/control/health";
  static final String READINESS_PATH = "/api/v1/internal/control/readiness";
  static final String DIAGNOSTICS_PATH = "/api/v1/internal/control/diagnostics";

  static final String READ_PERMISSION = "STAFF_CONTROL_READ";
  static final String DIAGNOSE_PERMISSION = "STAFF_CONTROL_DIAGNOSE";

  static final int MAX_RESPONSE_BYTES = 64 * 1024;

  record ControlResponse(int statusCode, byte[] bodyBytes) {
    ControlResponse {
      bodyBytes = bodyBytes.clone();
    }

    String body() {
      try {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bodyBytes))
            .toString();
      } catch (CharacterCodingException invalidUtf8) {
        throw new InvalidControlResponseException();
      }
    }
  }

  /** Transport-level failure (connect/timeout/protocol/TLS) that never carries a secret. */
  static final class ControlTransportException extends RuntimeException {
    ControlTransportException(String message) {
      super(message);
    }
  }

  static final class InvalidControlResponseException extends RuntimeException {}

  /**
   * Internal, content-free signal that the response exceeded {@link #MAX_RESPONSE_BYTES} while being
   * consumed. It never carries any response bytes, so it cannot leak partial body content. It is an
   * {@link IOException} so the JDK HTTP client surfaces it through the checked {@code send} contract
   * rather than rethrowing a raw unchecked exception.
   */
  static final class BoundedResponseExceededException extends IOException {
    BoundedResponseExceededException() {
      super("bounded size exceeded");
    }
  }

  private final CtlConfig config;
  private final ControlPlaneSigner signer;
  private final HttpClient httpClient;
  private final Clock clock;

  ControlApiClient(CtlConfig config, Clock clock) {
    this.config = config;
    this.signer = new ControlPlaneSigner(config.controlCredential().keyMaterialCopy(), config.credentialAlias());
    this.clock = clock;
    try {
      HttpClient.Builder builder = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
          .followRedirects(HttpClient.Redirect.NEVER);
      SSLContext sslContext = sslContext(config);
      if (sslContext != null) {
        builder.sslContext(sslContext);
      }
      this.httpClient = builder.build();
    } catch (GeneralSecurityException | IOException invalidTlsConfig) {
      throw new ControlTransportException("control TLS configuration is invalid");
    }
  }


  ControlApiClient(CtlConfig config, ControlPlaneSigner signer, HttpClient httpClient, Clock clock) {
    this.config = config;
    this.signer = signer;
    this.httpClient = httpClient;
    this.clock = clock;
  }
  ControlResponse get(String path) {
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(URI.create(config.coreBaseUrl() + path))
        .timeout(Duration.ofSeconds(config.timeoutSeconds()))
        .GET();
    signer.signedGetHeaders(path, clock.instant().getEpochSecond())
        .forEach(request::header);
    // Absolute total budget for connect + TLS + headers + complete bounded body read. The JDK request
    // timeout alone does not bound the streamed body once headers arrive, so the exchange is driven
    // asynchronously and governed by a single bounded await. On expiry the exchange future is
    // cancelled, which cancels the underlying connection and the bounded body subscription.
    CompletableFuture<HttpResponse<byte[]>> exchange =
        httpClient.sendAsync(request.build(), new BoundedByteArrayBodyHandler(MAX_RESPONSE_BYTES));
    try {
      HttpResponse<byte[]> response = exchange.get(config.timeoutSeconds(), TimeUnit.SECONDS);
      return new ControlResponse(response.statusCode(), response.body());
    } catch (TimeoutException deadlineExceeded) {
      exchange.cancel(true);
      throw new ControlTransportException("control API request exceeded the operation deadline");
    } catch (ExecutionException failed) {
      exchange.cancel(true);
      throw transportException(failed.getCause() == null ? failed : failed.getCause());
    } catch (InterruptedException interrupted) {
      exchange.cancel(true);
      Thread.currentThread().interrupt();
      throw new ControlTransportException("control API request interrupted");
    }
  }

  private static ControlTransportException transportException(Throwable transportFailure) {
    for (Throwable cause = transportFailure; cause != null; cause = cause.getCause()) {
      if (cause instanceof BoundedResponseExceededException) {
        return new ControlTransportException("control response exceeds the bounded size");
      }
    }
    // Exception class name only - never the message, which could echo server/response content.
    return new ControlTransportException(
        "control API request failed: " + transportFailure.getClass().getSimpleName());
  }

  Map<String, String> commandPaths() {
    return Map.of(
        "status", STATUS_PATH,
        "health", HEALTH_PATH,
        "readiness", READINESS_PATH,
        "diagnose", DIAGNOSTICS_PATH);
  }

  private static SSLContext sslContext(CtlConfig config) throws GeneralSecurityException, IOException {
    if (config.trustStorePath() == null) {
      return null;
    }
    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    char[] password = config.trustStorePassword();
    try (InputStream input = java.nio.file.Files.newInputStream(config.trustStorePath())) {
      trustStore.load(input, password);
    } finally {
      if (password != null) {
        java.util.Arrays.fill(password, '\0');
      }
    }
    TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trustStore);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, factory.getTrustManagers(), null);
    return context;
  }

  /** Produces a {@link BoundedBodySubscriber} regardless of the response status code. */
  static final class BoundedByteArrayBodyHandler implements HttpResponse.BodyHandler<byte[]> {
    private final int maxBytes;

    BoundedByteArrayBodyHandler(int maxBytes) {
      this.maxBytes = maxBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo responseInfo) {
      return new BoundedBodySubscriber(maxBytes);
    }
  }

  /**
   * Size-capping body subscriber. It runs inside the JDK HTTP exchange, so it participates in the
   * request timeout and cancels the upstream subscription the instant the cap is exceeded - a
   * stalled or oversized server can never accumulate an unbounded buffer or block past the deadline.
   */
  static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maxBytes;
    private final java.util.concurrent.CompletableFuture<byte[]> body =
        new java.util.concurrent.CompletableFuture<>();
    private final List<byte[]> chunks = new ArrayList<>();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private Flow.Subscription subscription;
    private long total;

    BoundedBodySubscriber(int maxBytes) {
      this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
      if (subscription != null) {
        // Exactly one subscription is retained; a duplicate is cancelled and ignored.
        incoming.cancel();
        return;
      }
      subscription = incoming;
      // Demand-driven, bounded chunks: request one buffer list at a time, re-request on each onNext.
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      if (terminated.get()) {
        return;
      }
      for (ByteBuffer buffer : items) {
        if (buffer == null) {
          fail(new BoundedResponseExceededException());
          return;
        }
        int remaining = buffer.remaining();
        // Overflow-safe accumulation: long arithmetic against a small int cap can never wrap.
        long projected = total + remaining;
        if (projected > maxBytes) {
          fail(new BoundedResponseExceededException());
          return;
        }
        byte[] accepted = new byte[remaining];
        buffer.get(accepted);
        chunks.add(accepted);
        total = projected;
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
      // Bound the surfaced error to its type: never propagate a server-supplied message/body.
      fail(new IOException("control response stream failed: " + throwable.getClass().getSimpleName()));
    }

    @Override
    public void onComplete() {
      if (!terminated.compareAndSet(false, true)) {
        return;
      }
      byte[] assembled = new byte[(int) total];
      int offset = 0;
      for (byte[] chunk : chunks) {
        System.arraycopy(chunk, 0, assembled, offset, chunk.length);
        offset += chunk.length;
      }
      chunks.clear();
      body.complete(assembled);
    }

    private void fail(Throwable cause) {
      if (!terminated.compareAndSet(false, true)) {
        return;
      }
      if (subscription != null) {
        subscription.cancel();
      }
      chunks.clear();
      body.completeExceptionally(cause);
    }
  }
}