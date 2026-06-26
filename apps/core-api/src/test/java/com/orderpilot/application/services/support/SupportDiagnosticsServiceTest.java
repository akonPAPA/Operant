package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.SupportInternalDtos.SupportTenantDiagnosticsResponse;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-51 — proves tenant diagnostics are a bounded, redacted, read-only summary: safe aggregates only,
 * no secret/payload/credential-shaped fields, and correct tenant-scoped counts and health derivation.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SupportDiagnosticsServiceTest {
  private static final Instant T0 = Instant.parse("2026-06-26T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  @Autowired private ProcessingJobRepository processingJobRepository;

  private SupportDiagnosticsService service() {
    return new SupportDiagnosticsService(processingJobRepository, CLOCK);
  }

  private ProcessingJob job(UUID tenantId) {
    return processingJobRepository.save(new ProcessingJob(tenantId, "EXTRACTION", "DOCUMENT", UUID.randomUUID(), 0, T0));
  }

  @Test
  void diagnosticsReturnSafeTenantScopedCountsAndHealth() {
    UUID tenantId = UUID.randomUUID();
    UUID otherTenant = UUID.randomUUID();
    job(tenantId);                 // PENDING
    job(tenantId);                 // PENDING
    ProcessingJob failed = job(tenantId);
    failed.markFailed("safe-token", T0);
    processingJobRepository.save(failed);
    job(otherTenant);              // must not leak into tenantId diagnostics

    SupportTenantDiagnosticsResponse response = service().diagnose(tenantId);

    assertThat(response.tenantId()).isEqualTo(tenantId);
    assertThat(response.totalJobs()).isEqualTo(3);
    assertThat(response.jobStatusCounts().get("PENDING")).isEqualTo(2);
    assertThat(response.jobStatusCounts().get("FAILED")).isEqualTo(1);
    assertThat(response.health()).isEqualTo("ATTENTION");
    assertThat(response.lastJobActivityAt()).isEqualTo(T0);
    assertThat(response.externalExecution()).isEqualTo("DISABLED");
    assertThat(response.scope()).isEqualTo("DIAGNOSTICS");
  }

  @Test
  void healthyWhenNoFailuresAndNoActivityWhenEmpty() {
    UUID healthy = UUID.randomUUID();
    job(healthy);
    assertThat(service().diagnose(healthy).health()).isEqualTo("HEALTHY");

    UUID empty = UUID.randomUUID();
    SupportTenantDiagnosticsResponse emptyResponse = service().diagnose(empty);
    assertThat(emptyResponse.health()).isEqualTo("NO_RECENT_ACTIVITY");
    assertThat(emptyResponse.totalJobs()).isZero();
    assertThat(emptyResponse.lastJobActivityAt()).isNull();
  }

  @Test
  void diagnosticsResponseExposesNoSecretOrRawPayloadShapedFields() {
    // Structural proof: the diagnostics contract carries only safe summary fields — no field name hints at
    // a secret, credential, token, raw document/webhook payload, AI prompt, or stack trace.
    String[] forbidden = {
        "secret", "credential", "password", "token", "apikey", "payload", "document", "webhook",
        "prompt", "stacktrace", "rawbody", "connectorconfig"};
    for (RecordComponent component : SupportTenantDiagnosticsResponse.class.getRecordComponents()) {
      String name = component.getName().toLowerCase(Locale.ROOT);
      for (String banned : forbidden) {
        assertThat(name).as("diagnostics field %s must not be secret/payload-shaped", name).doesNotContain(banned);
      }
    }
  }
}
