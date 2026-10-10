package com.orderpilot.aibot.infrastructure.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "operant.ai")
public class OperantAiProperties {
  private boolean enabled = false;
  private String provider = "gemini";
  private String dataPolicy = "SYNTHETIC_ONLY";
  private int maximumAttempts = 3;
  private int maximumInputChars = 8000;
  private int maximumOutputTokens = 1024;
  private final Worker worker = new Worker();

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getProvider() { return provider; }
  public void setProvider(String provider) { this.provider = provider; }
  public String getDataPolicy() { return dataPolicy; }
  public void setDataPolicy(String dataPolicy) { this.dataPolicy = dataPolicy; }
  public int getMaximumAttempts() { return maximumAttempts; }
  public void setMaximumAttempts(int maximumAttempts) {
    if (maximumAttempts < 1 || maximumAttempts > 5) {
      throw new IllegalArgumentException("maximum_attempts_out_of_bounds");
    }
    this.maximumAttempts = maximumAttempts;
  }
  public int getMaximumInputChars() { return maximumInputChars; }
  public void setMaximumInputChars(int maximumInputChars) { this.maximumInputChars = maximumInputChars; }
  public int getMaximumOutputTokens() { return maximumOutputTokens; }
  public void setMaximumOutputTokens(int maximumOutputTokens) {
    if (maximumOutputTokens < 1 || maximumOutputTokens > 8192) {
      throw new IllegalArgumentException("maximum_output_tokens_out_of_bounds");
    }
    this.maximumOutputTokens = maximumOutputTokens;
  }
  public Worker getWorker() { return worker; }
  public boolean isWorkerEnabled() { return worker.isEnabled(); }
  public int getWorkerBatchSize() { return worker.getBatchSize(); }
  public Duration getWorkerLeaseDuration() { return worker.getLeaseDuration(); }
  public long getWorkerFixedDelayMs() { return worker.getFixedDelayMs(); }
  public Duration getWorkerRetryBaseDelay() { return worker.getRetryBaseDelay(); }

  public boolean isSyntheticOnly() {
    return "SYNTHETIC_ONLY".equalsIgnoreCase(dataPolicy);
  }

  public boolean isJobExecutionEnabled() {
    return enabled && worker.enabled;
  }

  public static class Worker {
    private boolean enabled = false;
    private long fixedDelayMs = 5000L;
    private int batchSize = 5;
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration retryBaseDelay = Duration.ofSeconds(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getFixedDelayMs() { return fixedDelayMs; }
    public void setFixedDelayMs(long fixedDelayMs) {
      if (fixedDelayMs < 1000L || fixedDelayMs > 300_000L) {
        throw new IllegalArgumentException("fixed_delay_out_of_bounds");
      }
      this.fixedDelayMs = fixedDelayMs;
    }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) {
      if (batchSize < 1 || batchSize > 20) {
        throw new IllegalArgumentException("batch_size_out_of_bounds");
      }
      this.batchSize = batchSize;
    }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) {
      if (leaseDuration == null || leaseDuration.toSeconds() < 16 || leaseDuration.toSeconds() > 300) {
        throw new IllegalArgumentException("lease_duration_out_of_bounds");
      }
      this.leaseDuration = leaseDuration;
    }
    public Duration getRetryBaseDelay() { return retryBaseDelay; }
    public void setRetryBaseDelay(Duration retryBaseDelay) {
      if (retryBaseDelay == null || retryBaseDelay.isNegative() || retryBaseDelay.isZero()) {
        throw new IllegalArgumentException("retry_base_delay_out_of_bounds");
      }
      this.retryBaseDelay = retryBaseDelay;
    }
  }
}
