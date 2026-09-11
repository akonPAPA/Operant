package com.orderpilot.aibot.infrastructure.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "operant.ai.gemini")
public class GeminiProviderProperties {
  private String baseUrl = "https://generativelanguage.googleapis.com";
  private String model = "gemini-2.5-flash-lite";
  private String apiKey = "";
  private Duration connectTimeout = Duration.ofSeconds(2);
  private Duration readTimeout = Duration.ofSeconds(15);

  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }
  public String getApiKey() { return apiKey; }
  public void setApiKey(String apiKey) { this.apiKey = apiKey; }
  public Duration getConnectTimeout() { return connectTimeout; }
  public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
  public Duration getReadTimeout() { return readTimeout; }
  public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

  public boolean hasApiKey() {
    return apiKey != null && !apiKey.isBlank() && !apiKey.contains("CHANGE_ME") && !apiKey.equals("placeholder");
  }
}
