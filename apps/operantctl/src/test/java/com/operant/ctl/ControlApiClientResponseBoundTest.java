package com.operant.ctl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class ControlApiClientResponseBoundTest {
  private static final String SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

  @Test
  void smallBoundedResponseSucceedsAndClosesStream() {
    TrackedInputStream body = new TrackedInputStream("{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8));
    ControlApiClient.ControlResponse response = client(body).get(ControlApiClient.HEALTH_PATH);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    assertThat(body.closed()).isTrue();
  }

  @Test
  void exactlyMaxResponseBytesSucceedsAndClosesStream() {
    byte[] payload = "x".repeat(ControlApiClient.MAX_RESPONSE_BYTES).getBytes(StandardCharsets.UTF_8);
    TrackedInputStream body = new TrackedInputStream(payload);
    ControlApiClient.ControlResponse response = client(body).get(ControlApiClient.STATUS_PATH);

    assertThat(response.body().getBytes(StandardCharsets.UTF_8)).hasSize(ControlApiClient.MAX_RESPONSE_BYTES);
    assertThat(body.closed()).isTrue();
  }

  @Test
  void maxResponseBytesPlusOneIsRejectedAndClosedWithoutContentLeak() {
    String secretLikeBody = "S".repeat(ControlApiClient.MAX_RESPONSE_BYTES + 1);
    TrackedInputStream body = new TrackedInputStream(secretLikeBody.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> client(body).get(ControlApiClient.STATUS_PATH))
        .isInstanceOf(ControlApiClient.ControlTransportException.class)
        .hasMessageContaining("bounded size")
        .hasMessageNotContaining(secretLikeBody.substring(0, 32));
    assertThat(body.closed()).isTrue();
    assertThat(body.readCount()).isLessThanOrEqualTo(ControlApiClient.MAX_RESPONSE_BYTES + 1);
  }

  @Test
  void streamFailureIsTransportErrorAndClosesStream() {
    TrackedInputStream body = new TrackedInputStream("partial".getBytes(StandardCharsets.UTF_8), true);

    assertThatThrownBy(() -> client(body).get(ControlApiClient.STATUS_PATH))
        .isInstanceOf(ControlApiClient.ControlTransportException.class)
        .hasMessageContaining("IOException")
        .hasMessageNotContaining("partial");
    assertThat(body.closed()).isTrue();
  }

  private static ControlApiClient client(TrackedInputStream body) {
    CtlConfig config = new CtlConfig(
        "http://127.0.0.1:8080",
        "ops-prod",
        new ControlCredential(SECRET),
        10,
        true,
        null,
        null);
    return new ControlApiClient(
        config,
        new ControlPlaneSigner(new ControlCredential(SECRET).keyMaterialCopy(), "ops-prod"),
        new StubHttpClient(body),
        Clock.systemUTC());
  }

  private static final class TrackedInputStream extends InputStream {
    private final ByteArrayInputStream delegate;
    private final boolean failAfterFirstRead;
    private boolean closed;
    private int readCount;

    private TrackedInputStream(byte[] bytes) {
      this(bytes, false);
    }

    private TrackedInputStream(byte[] bytes, boolean failAfterFirstRead) {
      this.delegate = new ByteArrayInputStream(bytes);
      this.failAfterFirstRead = failAfterFirstRead;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (failAfterFirstRead && readCount > 0) {
        throw new IOException("simulated stream failure");
      }
      int read = delegate.read(b, off, len);
      if (read > 0) {
        readCount += read;
      }
      return read;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int read = read(one, 0, 1);
      return read == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public void close() throws IOException {
      closed = true;
      delegate.close();
    }

    boolean closed() {
      return closed;
    }

    int readCount() {
      return readCount;
    }
  }

  private static final class StubHttpClient extends HttpClient {
    private final TrackedInputStream body;

    private StubHttpClient(TrackedInputStream body) {
      this.body = body;
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
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      return (HttpResponse<T>) new StubResponse(body);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException("not used");
    }
  }

  private record StubResponse(InputStream body) implements HttpResponse<InputStream> {
    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public Optional<HttpResponse<InputStream>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
    }

    @Override
    public URI uri() {
      return URI.create("http://127.0.0.1:8080");
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }
  }
}