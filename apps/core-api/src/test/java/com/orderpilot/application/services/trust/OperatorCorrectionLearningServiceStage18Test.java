package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.OperatorCorrectionLearningDtos.CorrectionLearningProjectionResponse;
import com.orderpilot.application.services.trust.OperatorCorrectionLearningService.RecordCorrectionCommand;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.learning.OperatorCorrectionLearningRecord;
import com.orderpilot.domain.trust.learning.OperatorCorrectionStatus;
import com.orderpilot.domain.trust.learning.OperatorCorrectionType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-18 Operator Correction Learning Loop — hashing of raw values, deterministic eligibility,
 * approval/rejection gating, price/stock non-authoritative handling, and tenant isolation.
 */
@SpringBootTest
@ActiveProfiles("test")
class OperatorCorrectionLearningServiceStage18Test {
  @Autowired private OperatorCorrectionLearningService service;

  private RecordCorrectionCommand cmd(UUID tenantId, OperatorCorrectionType type, BigDecimal confidence,
      String previousValue, String correctedValue) {
    return new RecordCorrectionCommand(tenantId, type, AiMemorySourceType.OPERATOR_CORRECTION,
        UUID.randomUUID(), "PRODUCT", UUID.randomUUID(), "alias", previousValue, correctedValue,
        "canonical", "Operator correction summary", confidence, null);
  }

  // ----------------------------- 9. raw values hashed, never stored -----------------------------

  @Test
  void recordStoresHashesNotRawValues() {
    UUID tenantId = UUID.randomUUID();
    OperatorCorrectionLearningRecord rec = service.recordCorrection(
        cmd(tenantId, OperatorCorrectionType.PRODUCT_ALIAS, new BigDecimal("0.90"), "raw-old", "raw-new"));

    assertThat(rec.getPreviousValueHash()).isEqualTo(OperatorCorrectionLearningService.sha256Hex("raw-old"));
    assertThat(rec.getCorrectedValueHash()).isEqualTo(OperatorCorrectionLearningService.sha256Hex("raw-new"));
    assertThat(rec.getPreviousValueHash()).hasSize(64).doesNotContain("raw-old");
    assertThat(rec.getCorrectedValueHash()).doesNotContain("raw-new");
    assertThat(rec.getStatus()).isEqualTo(OperatorCorrectionStatus.RECORDED);
    assertThat(rec.isLearningEligible()).isTrue();
  }

  // ----------------------------- 10. approve publishes event + marks eligible -----------------------------

  @Test
  void approvePublishesProjectionEventAndMarksApproved() {
    UUID tenantId = UUID.randomUUID();
    OperatorCorrectionLearningRecord rec = service.recordCorrection(
        cmd(tenantId, OperatorCorrectionType.PRODUCT_ALIAS, new BigDecimal("0.90"), "a", "b"));

    CorrectionLearningProjectionResponse response =
        service.approveCorrectionForLearning(tenantId, rec.getId(), null);

    assertThat(response.publishedEventId()).isNotNull();
    assertThat(response.status()).isEqualTo(OperatorCorrectionStatus.APPROVED_FOR_LEARNING.name());
    OperatorCorrectionLearningRecord reloaded = service.getCorrection(tenantId, rec.getId());
    assertThat(reloaded.getStatus()).isEqualTo(OperatorCorrectionStatus.APPROVED_FOR_LEARNING);
    assertThat(reloaded.isLearningEligible()).isTrue();
  }

  // ----------------------------- 11. reject blocks future approval -----------------------------

  @Test
  void rejectBlocksProjectionAndApproval() {
    UUID tenantId = UUID.randomUUID();
    OperatorCorrectionLearningRecord rec = service.recordCorrection(
        cmd(tenantId, OperatorCorrectionType.PRODUCT_ALIAS, new BigDecimal("0.90"), "a", "b"));

    OperatorCorrectionLearningRecord rejected =
        service.rejectCorrection(tenantId, rec.getId(), "not a reusable pattern", null);
    assertThat(rejected.getStatus()).isEqualTo(OperatorCorrectionStatus.REJECTED);

    assertThatThrownBy(() -> service.approveCorrectionForLearning(tenantId, rec.getId(), null))
        .isInstanceOf(ConflictException.class);
  }

  // ----------------------------- 12. price/stock recorded but never authoritative -----------------------------

  @Test
  void priceOrStockCorrectionIsRecordedButNotApprovableForLearning() {
    UUID tenantId = UUID.randomUUID();
    OperatorCorrectionLearningRecord rec = service.recordCorrection(cmd(tenantId,
        OperatorCorrectionType.PRICE_OR_STOCK_CORRECTION_BLOCKED, new BigDecimal("0.99"), "a", "b"));

    assertThat(rec.getStatus()).isEqualTo(OperatorCorrectionStatus.RECORDED);
    assertThat(rec.isLearningEligible()).isFalse();
    assertThatThrownBy(() -> service.approveCorrectionForLearning(tenantId, rec.getId(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Price/stock");
  }

  // ----------------------------- 14. low-confidence not eligible until approved -----------------------------

  @Test
  void lowConfidenceCorrectionIsNotEligibleUntilApproved() {
    UUID tenantId = UUID.randomUUID();
    OperatorCorrectionLearningRecord rec = service.recordCorrection(
        cmd(tenantId, OperatorCorrectionType.DOCUMENT_FIELD_MAPPING, new BigDecimal("0.30"), "a", "b"));
    assertThat(rec.isLearningEligible()).isFalse();

    service.approveCorrectionForLearning(tenantId, rec.getId(), null);
    OperatorCorrectionLearningRecord reloaded = service.getCorrection(tenantId, rec.getId());
    assertThat(reloaded.isLearningEligible()).isTrue();
    assertThat(reloaded.getStatus()).isEqualTo(OperatorCorrectionStatus.APPROVED_FOR_LEARNING);
  }

  // ----------------------------- 15. tenant isolation -----------------------------

  @Test
  void tenantCannotReadOrApproveAnotherTenantsCorrection() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    OperatorCorrectionLearningRecord rec = service.recordCorrection(
        cmd(tenantA, OperatorCorrectionType.PRODUCT_ALIAS, new BigDecimal("0.90"), "a", "b"));

    assertThatThrownBy(() -> service.getCorrection(tenantB, rec.getId()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.approveCorrectionForLearning(tenantB, rec.getId(), null))
        .isInstanceOf(NotFoundException.class);
  }
}
