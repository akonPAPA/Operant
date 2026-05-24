package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRun;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ExtractionReviewService.class, CoreConfiguration.class})
class ExtractionReviewTenantIsolationTest {
  @Autowired private ExtractionReviewService reviewService;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void tenantACannotReadTenantBExtractionResult() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    ExtractionRun runB = runRepository.save(new ExtractionRun(tenantB, "CHANNEL_MESSAGE", sourceId, null, "RULE_BASED", "mock", "mock", "stage4.prompt.v1", "stage4.v1", Instant.parse("2026-05-24T00:00:00Z")));
    ExtractionResult resultB = resultRepository.save(new ExtractionResult(tenantB, runB.getId(), "CHANNEL_MESSAGE", sourceId, "RFQ", "message", new BigDecimal("0.90"), "{\"advisoryOnly\":true}", "READY_FOR_VALIDATION", Instant.parse("2026-05-24T00:00:00Z")));

    TenantContext.setTenantId(tenantA);

    assertThatThrownBy(() -> reviewService.result(resultB.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Extraction result not found");
    assertThat(reviewService.resultsForSource("CHANNEL_MESSAGE", sourceId)).isEmpty();
  }
}
