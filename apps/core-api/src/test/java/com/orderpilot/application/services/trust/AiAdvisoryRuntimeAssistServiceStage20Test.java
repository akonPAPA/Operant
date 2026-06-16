package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.AiAdvisoryRuntimeAssistDtos.RuntimeAssistHintDto;
import com.orderpilot.api.dto.AiAdvisoryRuntimeAssistDtos.RuntimeAssistResponse;
import com.orderpilot.application.services.trust.AiAdvisoryRuntimeAssistService.AssistCommand;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.CreateMemoryCommand;
import com.orderpilot.domain.trust.ai.AiMemoryActorType;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationReasonCode;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryStatus;
import com.orderpilot.domain.trust.ai.AiMemoryType;
import com.orderpilot.domain.trust.ai.RuntimeAssistContextType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-20 Layer A — AI Advisory Runtime Assist. Read-only, tenant-scoped, bounded, explainable, advisory
 * transform over OP-CAP-19 retrieval for the TRUST_VALIDATION_REVIEW context. Hints suggest; they never
 * decide, mutate, or broaden into a tenant-wide scan.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiAdvisoryRuntimeAssistServiceStage20Test {
  @Autowired private AiAdvisoryRuntimeAssistService assist;
  @Autowired private AiMemoryGovernanceService memory;

  private AiMemoryRecord create(UUID tenantId, AiMemoryNamespace ns, String key,
      AiMemoryAuthorityLevel authority, BigDecimal confidence) {
    return memory.createMemoryRecord(new CreateMemoryCommand(tenantId, ns, key, AiMemoryType.HINT, authority,
        AiMemorySourceType.DOCUMENT_TRUST_RUN, UUID.randomUUID(), "ref:safe", "Hint title",
        "Safe bounded advisory summary", "norm-value", confidence, 5, null, null, null));
  }

  private AssistCommand command(UUID tenantId, String lookupKey, Integer maxHints) {
    return new AssistCommand(tenantId, RuntimeAssistContextType.TRUST_VALIDATION_REVIEW,
        UUID.randomUUID(), null, lookupKey, maxHints);
  }

  // ----------------------------- exact match ranks first, advisory + explainable -----------------------------

  @Test
  void exactContextMatchRanksFirstAndIsAdvisoryOnly() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.TRUST_SIGNAL_HINT, "other", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));
    create(tenantId, AiMemoryNamespace.TRUST_SIGNAL_HINT, "exact", AiMemoryAuthorityLevel.LOW, new BigDecimal("0.60"));

    RuntimeAssistResponse resp = assist.assist(command(tenantId, "exact", 10));

    assertThat(resp.advisoryOnly()).isTrue();
    assertThat(resp.deterministicValidationRequired()).isNotEmpty();
    assertThat(resp.taskType()).isEqualTo("TRUST_SIGNAL_EXPLANATION");
    RuntimeAssistHintDto top = resp.hints().get(0);
    assertThat(top.rank()).isEqualTo(1);
    assertThat(top.title()).contains("exact");
    assertThat(top.reasonCodes()).contains("EXACT_KEY_MATCH");
    assertThat(top.applicability()).isEqualTo("DIRECT_CONTEXT_MATCH");
    assertThat(top.safetyLevel()).isEqualTo("ADVISORY_ONLY");
    assertThat(top.sourceAuthority()).startsWith("ADVISORY_MEMORY/");
    assertThat(top.advisoryOnly()).isTrue();
    assertThat(top.evidenceSummary()).isNotBlank();
  }

  // ----------------------------- invalidated memory excluded -----------------------------

  @Test
  void invalidatedMemoryIsNotReturned() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord bad = create(tenantId, AiMemoryNamespace.TRUST_SIGNAL_HINT, "bad", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));
    memory.invalidateMemoryRecord(tenantId, bad.getId(), AiMemoryInvalidationReasonCode.CONFLICTING_EVIDENCE,
        "newer evidence", AiMemoryActorType.OPERATOR, UUID.randomUUID());

    assertThat(assist.assist(command(tenantId, null, 10)).hints()).isEmpty();
  }

  // ----------------------------- maxHints clamp -----------------------------

  @Test
  void maxHintsIsClamped() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.TRUST_SIGNAL_HINT, "a", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));

    RuntimeAssistResponse resp = assist.assist(command(tenantId, null, 1000));

    assertThat(resp.requestedMaxHints()).isEqualTo(AiAdvisoryRuntimeAssistService.MAX_HINTS);
  }

  // ----------------------------- tenant isolation -----------------------------

  @Test
  void tenantCannotReceiveAnotherTenantsMemory() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    create(tenantA, AiMemoryNamespace.TRUST_SIGNAL_HINT, "iso", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));

    assertThat(assist.assist(command(tenantB, null, 10)).hints()).isEmpty();
  }

  // ----------------------------- missing context does not broaden the search -----------------------------

  @Test
  void unrelatedNamespaceMemoryIsNotPulledIntoTrustContext() {
    UUID tenantId = UUID.randomUUID();
    // Memory exists, but in a namespace the trust/validation context never considers.
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "product", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));

    RuntimeAssistResponse resp = assist.assist(command(tenantId, "anything", 10));

    assertThat(resp.returnedCount()).isZero();
    assertThat(resp.hints()).isEmpty();
  }

  // ----------------------------- summaries are sanitized/length-bounded -----------------------------

  @Test
  void summariesAreBoundedAndSafe() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.VALIDATION_EXPLANATION, "exp", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));

    RuntimeAssistResponse resp = assist.assist(command(tenantId, null, 10));

    for (RuntimeAssistHintDto hint : resp.hints()) {
      assertThat(hint.summary()).isEqualTo("Safe bounded advisory summary");
      assertThat(hint.summary().length()).isLessThanOrEqualTo(AiAdvisoryRuntimeAssistService.MAX_SUMMARY);
      assertThat(hint.title().length()).isLessThanOrEqualTo(AiAdvisoryRuntimeAssistService.MAX_TITLE);
    }
  }

  // ----------------------------- read-only: no business/memory mutation -----------------------------

  @Test
  void assistDoesNotMutateMemory() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord record = create(tenantId, AiMemoryNamespace.TRUST_SIGNAL_HINT, "keep", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));

    assist.assist(command(tenantId, "keep", 10));

    AiMemoryRecord after = memory.getRecord(tenantId, record.getId());
    assertThat(after.getStatus()).isEqualTo(AiMemoryStatus.ACTIVE);
    assertThat(after.getAccessCount()).isZero();
  }
}
