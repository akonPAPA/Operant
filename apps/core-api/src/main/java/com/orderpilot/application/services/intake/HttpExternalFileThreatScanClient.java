package com.orderpilot.application.services.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orderpilot.intake.malware-scan.mode", havingValue = "external")
public class HttpExternalFileThreatScanClient implements ExternalFileThreatScanClient {

  private final URI endpoint;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public HttpExternalFileThreatScanClient(
      @Value("${orderpilot.intake.malware-scan.external-endpoint}") String endpoint,
      @Value("${orderpilot.intake.malware-scan.connect-timeout-ms:2000}") long connectTimeoutMs,
      @Value("${orderpilot.intake.malware-scan.read-timeout-ms:5000}") long readTimeoutMs,
      ObjectMapper objectMapper) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalStateException("external malware-scan endpoint is required when mode=external");
    }
    this.endpoint = URI.create(endpoint.trim());
    this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
    this.readTimeout = Duration.ofMillis(readTimeoutMs);
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
  }

  @Override
  public FileThreatScanService.Verdict scan(byte[] bytes, String canonicalContentType) {
    try {
      String body = objectMapper.writeValueAsString(
          Map.of(
              "contentType", canonicalContentType == null ? "application/octet-stream" : canonicalContentType,
              "contentBase64", Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes)));
      HttpRequest request = HttpRequest.newBuilder(endpoint)
          .timeout(readTimeout)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return FileThreatScanService.Verdict.QUARANTINED;
      }
      JsonNode json = objectMapper.readTree(response.body());
      String verdict = json.path("verdict").asText("QUARANTINED").toUpperCase();
      return switch (verdict) {
        case "CLEAN" -> FileThreatScanService.Verdict.CLEAN;
        case "REJECTED" -> FileThreatScanService.Verdict.REJECTED;
        default -> FileThreatScanService.Verdict.QUARANTINED;
      };
    } catch (Exception ex) {
      return FileThreatScanService.Verdict.QUARANTINED;
    }
  }
}
