package com.orderpilot.application.services.intake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "orderpilot.intake.malware-scan.mode", havingValue = "external")
public class ConfigurableExternalFileThreatScanService implements FileThreatScanService {

  private final ExternalFileThreatScanClient client;

  public ConfigurableExternalFileThreatScanService(ExternalFileThreatScanClient client) {
    this.client = client;
  }

  @Override
  public Verdict scan(byte[] bytes, String canonicalContentType) {
    try {
      return client.scan(bytes, canonicalContentType);
    } catch (RuntimeException ex) {
      return Verdict.QUARANTINED;
    }
  }
}
