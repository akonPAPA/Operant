package com.orderpilot.aibot.infrastructure.configuration;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "operant.aibot.runtime")
public class BotRuntimeProperties {
  private boolean previewEnabled = true;
  private BigDecimal minimumDefaultConfidence = new BigDecimal("0.70");
  private int maximumIntentsPerDefinition = 20;
  private int maximumResponseLength = 1000;
  private boolean handoffOnProviderFailure = true;

  public boolean isPreviewEnabled() { return previewEnabled; }
  public void setPreviewEnabled(boolean previewEnabled) { this.previewEnabled = previewEnabled; }
  public BigDecimal getMinimumDefaultConfidence() { return minimumDefaultConfidence; }
  public void setMinimumDefaultConfidence(BigDecimal minimumDefaultConfidence) {
    this.minimumDefaultConfidence = minimumDefaultConfidence;
  }
  public int getMaximumIntentsPerDefinition() { return maximumIntentsPerDefinition; }
  public void setMaximumIntentsPerDefinition(int maximumIntentsPerDefinition) {
    this.maximumIntentsPerDefinition = maximumIntentsPerDefinition;
  }
  public int getMaximumResponseLength() { return maximumResponseLength; }
  public void setMaximumResponseLength(int maximumResponseLength) {
    this.maximumResponseLength = maximumResponseLength;
  }
  public boolean isHandoffOnProviderFailure() { return handoffOnProviderFailure; }
  public void setHandoffOnProviderFailure(boolean handoffOnProviderFailure) {
    this.handoffOnProviderFailure = handoffOnProviderFailure;
  }
}
