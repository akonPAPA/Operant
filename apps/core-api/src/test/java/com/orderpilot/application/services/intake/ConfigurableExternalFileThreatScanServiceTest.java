package com.orderpilot.application.services.intake;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigurableExternalFileThreatScanServiceTest {

  @Test
  void returnsClientVerdictWhenClean() {
    var service = new ConfigurableExternalFileThreatScanService((bytes, type) -> FileThreatScanService.Verdict.CLEAN);
    assertThat(service.scan(new byte[] {1}, "application/pdf")).isEqualTo(FileThreatScanService.Verdict.CLEAN);
  }

  @Test
  void quarantinesWhenClientThrows() {
    var service = new ConfigurableExternalFileThreatScanService((bytes, type) -> {
      throw new IllegalStateException("scanner down");
    });
    assertThat(service.scan(new byte[] {1}, "application/pdf")).isEqualTo(FileThreatScanService.Verdict.QUARANTINED);
  }

  @Test
  void propagatesRejectedVerdict() {
    var service = new ConfigurableExternalFileThreatScanService((bytes, type) -> FileThreatScanService.Verdict.REJECTED);
    assertThat(service.scan(new byte[] {1}, "application/pdf")).isEqualTo(FileThreatScanService.Verdict.REJECTED);
  }
}
