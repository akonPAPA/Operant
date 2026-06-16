package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryHintDto;
import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryRetrievalResponse;
import com.orderpilot.application.services.trust.AiAdvisoryMemoryRetrievalService.RetrievalCommand;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.CreateMemoryCommand;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.SupersedeMemoryCommand;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.AiMemoryActorType;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationReasonCode;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-19 Layer B — Advisory AI Memory Retrieval Ranking. Deterministic, tenant-scoped, bounded ranking
 * over governed OP-CAP-17F memory; advisory-only; never authoritative; excludes ineligible memory.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiAdvisoryMemoryRetrievalServiceStage19Test {
  @Autowired private AiAdvisoryMemoryRetrievalService retrieval;
  @Autowired private AiMemoryGovernanceService memory;

  private AiMemoryRecord create(UUID tenantId, AiMemoryNamespace ns, String key,
      AiMemoryAuthorityLevel authority, BigDecimal confidence) {
    return memory.createMemoryRecord(new CreateMemoryCommand(tenantId, ns, key, AiMemoryType.HINT, authority,
        AiMemorySourceType.DOCUMENT_TRUST_RUN, UUID.randomUUID(), "ref:safe", "Hint title",
        "Safe bounded advisory summary", "norm-value", confidence, 5, null, null, null));
  }

  private RetrievalCommand command(UUID tenantId, AiAdvisoryTaskType task, AiMemoryNamespace ns,
      String lookupKey, Integer maxResults, BigDecimal minConfidence) {
    return new RetrievalCommand(tenantId, task, List.of(ns), List.of(), null, null, lookupKey,
        maxResults, minConfidence, false, false);
  }

  // ----------------------------- 11. exact key ranks highest -----------------------------

  @Test
  void exactKeyMatchRanksHighest() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "other", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "exact", AiMemoryAuthorityLevel.LOW, new BigDecimal("0.60"));

    AdvisoryMemoryRetrievalResponse resp = retrieval.retrieve(
        command(tenantId, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "exact", 10, null));

    assertThat(resp.hints().get(0).memoryKey()).isEqualTo("exact");
    assertThat(resp.hints().get(0).reasonCodes()).contains("EXACT_KEY_MATCH");
  }

  // ----------------------------- 12. human-approved outranks lower authority -----------------------------

  @Test
  void humanApprovedOutranksLowerAuthority() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "low-high-conf", AiMemoryAuthorityLevel.LOW, new BigDecimal("0.95"));
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "human", AiMemoryAuthorityLevel.HUMAN_APPROVED, new BigDecimal("0.60"));

    AdvisoryMemoryRetrievalResponse resp = retrieval.retrieve(
        command(tenantId, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, 10, null));

    assertThat(resp.hints().get(0).memoryKey()).isEqualTo("human");
    assertThat(resp.hints().get(0).reasonCodes()).contains("HUMAN_APPROVED");
  }

  // ----------------------------- 13. invalidated excluded by default -----------------------------

  @Test
  void invalidatedMemoryExcludedByDefault() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord record = create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "inv", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));
    memory.invalidateMemoryRecord(tenantId, record.getId(), AiMemoryInvalidationReasonCode.CONFLICTING_EVIDENCE,
        "newer evidence", AiMemoryActorType.OPERATOR, UUID.randomUUID());

    AdvisoryMemoryRetrievalResponse resp = retrieval.retrieve(
        command(tenantId, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, 10, null));

    assertThat(resp.hints()).isEmpty();
  }

  // ----------------------------- 14. superseded excluded by default -----------------------------

  @Test
  void supersededMemoryExcludedByDefault() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord v1 = create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "ver", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));
    memory.supersedeMemoryRecord(new SupersedeMemoryCommand(tenantId, v1.getId(), null, AiMemoryAuthorityLevel.HIGH,
        "Hint title v2", "Refined advisory summary", "norm-2", new BigDecimal("0.96"), 5, null, "refined", UUID.randomUUID()));

    AdvisoryMemoryRetrievalResponse resp = retrieval.retrieve(
        command(tenantId, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "ver", 10, null));

    assertThat(resp.hints()).hasSize(1);
    assertThat(resp.hints().get(0).memoryRecordId()).isNotEqualTo(v1.getId());
  }

  // ----------------------------- 15. minConfidence filter -----------------------------

  @Test
  void minConfidenceFiltersLowConfidenceMemory() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.EXTRACTION_CORRECTION, "low", AiMemoryAuthorityLevel.MEDIUM, new BigDecimal("0.30"));

    // Default threshold (MIN_USABLE_CONFIDENCE 0.50) excludes the 0.30 record.
    assertThat(retrieval.retrieve(command(tenantId, AiAdvisoryTaskType.DOCUMENT_EXTRACTION_ASSIST,
        AiMemoryNamespace.EXTRACTION_CORRECTION, null, 10, null)).hints()).isEmpty();
    // Lowering the requested threshold includes it.
    assertThat(retrieval.retrieve(command(tenantId, AiAdvisoryTaskType.DOCUMENT_EXTRACTION_ASSIST,
        AiMemoryNamespace.EXTRACTION_CORRECTION, null, 10, new BigDecimal("0.10"))).hints()).hasSize(1);
  }

  // ----------------------------- 16. maxResults clamp -----------------------------

  @Test
  void maxResultsIsClamped() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "a", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));

    AdvisoryMemoryRetrievalResponse resp = retrieval.retrieve(
        command(tenantId, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, 1000, null));

    assertThat(resp.requestedMaxResults()).isEqualTo(AiAdvisoryMemoryRetrievalService.MAX_MAX_RESULTS);
  }

  // ----------------------------- 17. tenant isolation -----------------------------

  @Test
  void tenantCannotRetrieveAnotherTenantsMemory() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    create(tenantA, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "iso", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));

    assertThat(retrieval.retrieve(command(tenantB, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST,
        AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, 10, null)).hints()).isEmpty();
  }

  // ----------------------------- 18 & 19. advisoryOnly and safe fields -----------------------------

  @Test
  void retrievedHintsAreAdvisoryOnlyAndCarryOnlySafeFields() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.PAYMENT_MATCH_HINT, "pay", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));
    create(tenantId, AiMemoryNamespace.TRUST_SIGNAL_HINT, "trust", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));

    AdvisoryMemoryRetrievalResponse pay = retrieval.retrieve(command(tenantId,
        AiAdvisoryTaskType.PAYMENT_MATCH_ASSIST, AiMemoryNamespace.PAYMENT_MATCH_HINT, null, 10, null));
    AdvisoryMemoryRetrievalResponse trust = retrieval.retrieve(command(tenantId,
        AiAdvisoryTaskType.TRUST_SIGNAL_EXPLANATION, AiMemoryNamespace.TRUST_SIGNAL_HINT, null, 10, null));

    assertThat(pay.advisoryOnly()).isTrue();
    assertThat(trust.advisoryOnly()).isTrue();
    for (AdvisoryMemoryHintDto hint : trust.hints()) {
      assertThat(hint.advisoryOnly()).isTrue();
      assertThat(hint.summary()).isEqualTo("Safe bounded advisory summary");
    }
  }

  // ----------------------------- 20. empty bounded response -----------------------------

  @Test
  void unsupportedOrEmptyNamespaceReturnsBoundedEmptyResponse() {
    UUID tenantId = UUID.randomUUID();
    AdvisoryMemoryRetrievalResponse resp = retrieval.retrieve(command(tenantId,
        AiAdvisoryTaskType.BOT_RESPONSE_ASSIST, AiMemoryNamespace.BOT_CONVERSATION_SUMMARY, null, 10, null));

    assertThat(resp).isNotNull();
    assertThat(resp.hints()).isEmpty();
    assertThat(resp.returnedCount()).isZero();
  }
}
