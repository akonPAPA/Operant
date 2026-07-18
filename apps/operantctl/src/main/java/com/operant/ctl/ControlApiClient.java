package com.operant.ctl;

import java.io.ByteArrayOutputStream;
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
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Bounded HTTP client for the Core control-plane read surface. The command-to-path map is a fixed
 * allowlist: there is no way to request an arbitrary path, method, or body, and every request is
 * signed with the control-plane key. Response bodies are size-capped before parsing.
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
    try {
      HttpResponse<InputStream> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      byte[] body = readBounded(response.body());
      return new ControlResponse(response.statusCode(), body);
    } catch (IOException transportFailure) {
      throw new ControlTransportException(
          "control API request failed: " + transportFailure.getClass().getSimpleName());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ControlTransportException("control API request interrupted");
    }
  }


  private static byte[] readBounded(InputStream input) throws IOException {
    try (InputStream body = input; ByteArrayOutputStream out = new ByteArrayOutputStream(MAX_RESPONSE_BYTES)) {
      byte[] buffer = new byte[8192];
      int total = 0;
      int read;
      while ((read = body.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE_BYTES + 1 - total))) != -1) {
        total += read;
        if (total > MAX_RESPONSE_BYTES) {
          throw new ControlTransportException("control response exceeds the bounded size");
        }
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
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
}