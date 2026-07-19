package com.operant.ctl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

/**
 * WP-1 proof: the single configured operation deadline is an absolute total budget for connect + TLS
 * + headers + complete bounded body read, and the response is size-capped while it is consumed. The
 * realistic cases drive the real JDK {@link HttpClient} against a loopback {@code HttpServer}; the
 * subscriber cases drive the bounded {@link ControlApiClient.BoundedBodySubscriber} directly.
 */
class ControlApiClientResponseBoundTest {
  private static final String SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

  @Test
  void smallCompleteResponseSucceeds() throws Exception {
    withServer(
        fixedResponse(200, "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8)),
        server -> {
          ControlApiClient.ControlResponse response = realClient(server, 10).get(ControlApiClient.HEALTH_PATH);
          assertThat(response.statusCode()).isEqualTo(200);
          assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        });
  }

  @Test
  void exactlyMaxResponseBytesSucceeds() throws Exception {
    byte[] payload = "x".repeat(ControlApiClient.MAX_RESPONSE_BYTES).getBytes(StandardCharsets.UTF_8);
    withServer(
        fixedResponse(200, payload),
        server -> {
          ControlApiClient.ControlResponse response = realClient(server, 10).get(ControlApiClient.STATUS_PATH);
          assertThat(response.body().getBytes(StandardCharsets.UTF_8))
              .hasSize(ControlApiClient.MAX_RESPONSE_BYTES);
        });
  }

  @Test
  void maxResponseBytesPlusOneFailsClosedWithoutContentLeak() throws Exception {
    String secretLikeBody = "S".repeat(ControlApiClient.MAX_RESPONSE_BYTES + 1);
    withServer(
        fixedResponse(200, secretLikeBody.getBytes(StandardCharsets.UTF_8)),
        server ->
            assertThatThrownBy(() -> realClient(server, 10).get(ControlApiClient.STATUS_PATH))
                .isInstanceOf(ControlApiClient.ControlTransportException.class)
                .hasMessageContaining("bounded size")
                .hasMessageNotContaining(secretLikeBody.substring(0, 32)));
  }

  @Test
  void malformedUtf8IsRejectedWhenDecoded() throws Exception {
    byte[] invalidUtf8 = {(byte) 0xff, (byte) 0xfe, (byte) 0xfa};
    withServer(
        fixedResponse(200, invalidUtf8),
        server -> {
          ControlApiClient.ControlResponse response = realClient(server, 10).get(ControlApiClient.STATUS_PATH);
          assertThat(response.statusCode()).isEqualTo(200);
          assertThatThrownBy(response::body)
              .isInstanceOf(ControlApiClient.InvalidControlResponseException.class);
        });
  }

  @Test
  void headersThenPartialBodyThenPermanentStallTimesOutWithinBound() throws Exception {
    HttpHandler stall = exchange -> {
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream body = exchange.getResponseBody()) {
        body.write("{\"partialSecret\":\"".getBytes(StandardCharsets.UTF_8));
        body.flush();
        // Never send the rest; hold the connection open well past the client operation deadline.
        Thread.sleep(30_000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // Client cancelled/closed the exchange - expected.
      }
    };
    withServer(stall, server -> {
      long startNanos = System.nanoTime();
      Throwable thrown = catchThrowable(() -> realClient(server, 1).get(ControlApiClient.STATUS_PATH));
      long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

      assertThat(thrown).isInstanceOf(ControlApiClient.ControlTransportException.class);
      // No partial body, no credential/secret ever appears in the transport error.
      assertThat(thrown.getMessage()).doesNotContain("partialSecret").doesNotContain(SECRET);
      // Bounded well below the 30s server stall - the total deadline governs, not the body read.
      assertThat(elapsedMillis).isLessThan(15_000);
    });
  }

  @Test
  void nonSuccessResponseBodyIsCapturedNotThrown() throws Exception {
    withServer(
        fixedResponse(503, "{\"error\":\"down\"}".getBytes(StandardCharsets.UTF_8)),
        server -> {
          ControlApiClient.ControlResponse response = realClient(server, 10).get(ControlApiClient.STATUS_PATH);
          assertThat(response.statusCode()).isEqualTo(503);
        });
  }

  @Test
  void redirectsAreNotFollowed() throws Exception {
    HttpHandler redirect = exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    };
    withServer(redirect, server -> {
      ControlApiClient.ControlResponse response = realClient(server, 10).get(ControlApiClient.STATUS_PATH);
      // The 302 is returned as-is; the client never chases the redirect target.
      assertThat(response.statusCode()).isEqualTo(302);
    });
  }

  @Test
  void interruptionPreservesInterruptFlagAndMapsToTransport() {
    ControlApiClient client = new ControlApiClient(
        localConfig("http://127.0.0.1:8080", 10),
        new ControlPlaneSigner(new ControlCredential(SECRET).keyMaterialCopy(), "ops-prod"),
        new NeverCompletingHttpClient(),
        Clock.systemUTC());

    // Pre-interrupt so the bounded await throws InterruptedException deterministically.
    Thread.currentThread().interrupt();
    assertThatThrownBy(() -> client.get(ControlApiClient.STATUS_PATH))
        .isInstanceOf(ControlApiClient.ControlTransportException.class)
        .hasMessageContaining("interrupted");
    assertThat(Thread.interrupted()).isTrue();
  }

  // --- Direct subscriber contract -------------------------------------------------------------

  @Test
  void subscriberAssemblesExactBytesAndRequestsBoundedChunks() throws Exception {
    ControlApiClient.BoundedBodySubscriber subscriber =
        new ControlApiClient.BoundedBodySubscriber(ControlApiClient.MAX_RESPONSE_BYTES);
    RecordingSubscription subscription = new RecordingSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap("ab".getBytes(StandardCharsets.UTF_8))));
    subscriber.onNext(List.of(ByteBuffer.wrap("c".getBytes(StandardCharsets.UTF_8))));
    subscriber.onComplete();

    assertThat(new String(subscriber.getBody().toCompletableFuture().get(), StandardCharsets.UTF_8))
        .isEqualTo("abc");
    assertThat(subscription.requested.get()).isGreaterThanOrEqualTo(2);
    assertThat(subscription.cancelled.get()).isFalse();
  }

  @Test
  void subscriberRejectsNullBufferAndCancels() {
    ControlApiClient.BoundedBodySubscriber subscriber =
        new ControlApiClient.BoundedBodySubscriber(ControlApiClient.MAX_RESPONSE_BYTES);
    RecordingSubscription subscription = new RecordingSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(java.util.Collections.singletonList(null));

    assertThat(subscription.cancelled.get()).isTrue();
    assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().get())
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ControlApiClient.BoundedResponseExceededException.class);
  }

  @Test
  void subscriberCancelsWhenCapExceeded() {
    ControlApiClient.BoundedBodySubscriber subscriber =
        new ControlApiClient.BoundedBodySubscriber(4);
    RecordingSubscription subscription = new RecordingSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap("12345".getBytes(StandardCharsets.UTF_8))));

    assertThat(subscription.cancelled.get()).isTrue();
    assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().get())
        .hasCauseInstanceOf(ControlApiClient.BoundedResponseExceededException.class);
  }

  @Test
  void subscriberRetainsOneSubscriptionAndCancelsDuplicate() {
    ControlApiClient.BoundedBodySubscriber subscriber =
        new ControlApiClient.BoundedBodySubscriber(4);
    RecordingSubscription first = new RecordingSubscription();
    RecordingSubscription duplicate = new RecordingSubscription();
    subscriber.onSubscribe(first);
    subscriber.onSubscribe(duplicate);

    assertThat(first.cancelled.get()).isFalse();
    assertThat(duplicate.cancelled.get()).isTrue();
  }

  @Test
  void subscriberIgnoresDoubleCompletion() throws Exception {
    ControlApiClient.BoundedBodySubscriber subscriber =
        new ControlApiClient.BoundedBodySubscriber(16);
    subscriber.onSubscribe(new RecordingSubscription());
    subscriber.onNext(List.of(ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8))));
    subscriber.onComplete();
    // A late error after completion must not overwrite the completed body.
    subscriber.onError(new IllegalStateException("late"));

    assertThat(new String(subscriber.getBody().toCompletableFuture().get(), StandardCharsets.UTF_8))
        .isEqualTo("ok");
  }

  // --- Harness --------------------------------------------------------------------------------

  private interface ServerTest {
    void run(HttpServer server) throws Exception;
  }

  private static void withServer(HttpHandler handler, ServerTest test) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler);
    server.setExecutor(Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "control-test-server");
      thread.setDaemon(true);
      return thread;
    }));
    server.start();
    try {
      test.run(server);
    } finally {
      server.stop(0);
    }
  }

  private static HttpHandler fixedResponse(int status, byte[] body) {
    return exchange -> {
      exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    };
  }

  private static ControlApiClient realClient(HttpServer server, int timeoutSeconds) {
    return new ControlApiClient(
        localConfig("http://127.0.0.1:" + server.getAddress().getPort(), timeoutSeconds),
        Clock.systemUTC());
  }

  private static CtlConfig localConfig(String baseUrl, int timeoutSeconds) {
    return new CtlConfig(baseUrl, "ops-prod", new ControlCredential(SECRET), timeoutSeconds, true, null, null);
  }

  private static Throwable catchThrowable(Runnable runnable) {
    try {
      runnable.run();
      return null;
    } catch (Throwable thrown) {
      return thrown;
    }
  }

  private static final class RecordingSubscription implements Flow.Subscription {
    private final AtomicInteger requested = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    @Override
    public void request(long n) {
      requested.incrementAndGet();
    }

    @Override
    public void cancel() {
      cancelled.set(true);
    }
  }

  /** Minimal client whose async exchange never completes, so a pre-interrupted await is deterministic. */
  private static final class NeverCompletingHttpClient extends HttpClient {
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      return new CompletableFuture<>();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }
}
