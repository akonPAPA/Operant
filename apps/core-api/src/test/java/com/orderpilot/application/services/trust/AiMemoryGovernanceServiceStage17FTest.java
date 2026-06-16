package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.trust.AiMemoryGovernanceService.CreateMemoryCommand;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.SupersedeMemoryCommand;
import com.orderpilot.application.services.trust.AiRuntimeTraceService.RecordRuntimeTraceCommand;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.trust.ai.AiMemoryActorType;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationEvent;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationReasonCode;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryStatus;
import com.orderpilot.domain.trust.ai.AiMemoryType;
import com.orderpilot.domain.trust.ai.AiRuntimeStatus;
import com.orderpilot.domain.trust.ai.AiRuntimeTrace;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance — create/supersede/invalidate/expire,
 * sanitization, TTL/confidence gating, access tracking, runtime traces, and tenant isolation. Memory is
 * advisory and low-authority; deterministic backend services remain the source of truth.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiMemoryGovernanceServiceStage17FTest {
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-14T12:00:00Z"), ZoneOffset.UTC);
  private static final Clock LATER_CLOCK = Clock.fixed(Instant.parse("2026-06-14T13:00:00Z"), ZoneOffset.UTC);

  @Autowired private AiMemoryGovernanceService service;
  @Autowired private AiRuntimeTraceService traceService;
  @Autowired private AiMemoryPolicyService policy;
  @Autowired private AuditEventRepository auditEvents;

  @BeforeEach
  void fixClock() {
    ReflectionTestUtils.setField(service, "clock", FIXED_CLOCK);
    ReflectionTestUtils.setField(traceService, "clock", FIXED_CLOCK);
  }

  // ----------------------------- fixtures -----------------------------

  private CreateMemoryCommand create(UUID tenantId, AiMemoryNamespace ns, String key,
      AiMemoryAuthorityLevel authority, BigDecimal confidence, Long ttlSeconds) {
    return new CreateMemoryCommand(tenantId, ns, key, AiMemoryType.HINT, authority,
        AiMemorySourceType.DOCUMENT_TRUST_RUN, UUID.randomUUID(), "documentTrustRun:ref",
        "Alias hint", "Counterparty refers to part X as Y", "X->Y", confidence, 5, ttlSeconds, null, null);
  }

  // ----------------------------- 1. create -----------------------------

  @Test
  void createActiveMemoryRecordWithTenantScope() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord record = service.createMemoryRecord(
        create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "alias-1", AiMemoryAuthorityLevel.MEDIUM,
            new BigDecimal("0.90"), null));

    assertThat(record.getTenantId()).isEqualTo(tenantId);
    assertThat(record.getStatus()).isEqualTo(AiMemoryStatus.ACTIVE);
    assertThat(record.getVersion()).isEqualTo(1);
    assertThat(record.getConfidence()).isEqualByComparingTo("0.90");
    assertThat(auditEvents.findAll().stream()
        .anyMatch(e -> e.getAction().equals("AI_MEMORY_RECORD_CREATED"))).isTrue();
  }

  // ----------------------------- 2. duplicate rejected -----------------------------

  @Test
  void duplicateActiveNamespaceKeyIsRejected() {
    UUID tenantId = UUID.randomUUID();
    service.createMemoryRecord(create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "dup",
        AiMemoryAuthorityLevel.MEDIUM, new BigDecimal("0.90"), null));

    assertThatThrownBy(() -> service.createMemoryRecord(create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT,
        "dup", AiMemoryAuthorityLevel.MEDIUM, new BigDecimal("0.90"), null)))
        .isInstanceOf(ConflictException.class);
  }

  // ----------------------------- 3. active search tenant-scoped -----------------------------

  @Test
  void activeSearchReturnsOnlySameTenantRecords() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    service.createMemoryRecord(create(tenantA, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "a",
        AiMemoryAuthorityLevel.MEDIUM, new BigDecimal("0.90"), null));

    assertThat(service.searchMemory(tenantA, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, false, false, 25))
        .hasSize(1);
    assertThat(service.searchMemory(tenantB, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, false, false, 25))
        .isEmpty();
  }

  // ----------------------------- 4 & 5. TTL expiry -----------------------------

  @Test
  void expiredRecordsAreExcludedByDefaultAndReturnedWithIncludeExpired() {
    UUID tenantId = UUID.randomUUID();
    service.createMemoryRecord(create(tenantId, AiMemoryNamespace.DOCUMENT_TEMPLATE, "ttl",
        AiMemoryAuthorityLevel.MEDIUM, new BigDecimal("0.90"), 600L)); // expires NOW+10m

    // Advance the clock past the TTL: default search excludes it, includeExpired returns it.
    ReflectionTestUtils.setField(service, "clock", LATER_CLOCK);
    assertThat(service.searchMemory(tenantId, AiMemoryNamespace.DOCUMENT_TEMPLATE, null, false, false, 25))
        .isEmpty();
    assertThat(service.searchMemory(tenantId, AiMemoryNamespace.DOCUMENT_TEMPLATE, null, true, false, 25))
        .hasSize(1);
  }

  // ----------------------------- 6 & 7. low confidence -----------------------------

  @Test
  void lowConfidenceExcludedByDefaultAndReturnedWithIncludeLowConfidence() {
    UUID tenantId = UUID.randomUUID();
    service.createMemoryRecord(create(tenantId, AiMemoryNamespace.EXTRACTION_CORRECTION, "low",
        AiMemoryAuthorityLevel.MEDIUM, new BigDecimal("0.30"), null));

    assertThat(service.searchMemory(tenantId, AiMemoryNamespace.EXTRACTION_CORRECTION, null, false, false, 25))
        .isEmpty();
    assertThat(service.searchMemory(tenantId, AiMemoryNamespace.EXTRACTION_CORRECTION, null, false, true, 25))
        .hasSize(1);
  }

  // ----------------------------- 8 & 9. invalidation -----------------------------

  @Test
  void invalidationChangesStatusCreatesEventAndDropsFromActiveSearch() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord record = service.createMemoryRecord(create(tenantId,
        AiMemoryNamespace.VALIDATION_EXPLANATION, "inv", AiMemoryAuthorityLevel.MEDIUM,
        new BigDecimal("0.90"), null));

    AiMemoryRecord invalidated = service.invalidateMemoryRecord(tenantId, record.getId(),
        AiMemoryInvalidationReasonCode.CONFLICTING_EVIDENCE, "Newer document contradicts this",
        AiMemoryActorType.OPERATOR, UUID.randomUUID());

    assertThat(invalidated.getStatus()).isEqualTo(AiMemoryStatus.INVALIDATED);
    List<AiMemoryInvalidationEvent> events = service.listInvalidations(tenantId, record.getId());
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getReasonCode()).isEqualTo(AiMemoryInvalidationReasonCode.CONFLICTING_EVIDENCE);
    assertThat(events.get(0).getNewStatus()).isEqualTo(AiMemoryStatus.INVALIDATED);
    assertThat(service.searchMemory(tenantId, AiMemoryNamespace.VALIDATION_EXPLANATION, null, false, false, 25))
        .isEmpty();
    assertThat(auditEvents.findAll().stream()
        .anyMatch(e -> e.getAction().equals("AI_MEMORY_RECORD_INVALIDATED"))).isTrue();
  }

  // ----------------------------- 10. access tracking -----------------------------

  @Test
  void recordAccessIncrementsCountAndTimestamp() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord record = service.createMemoryRecord(create(tenantId,
        AiMemoryNamespace.PRODUCT_ALIAS_HINT, "acc", AiMemoryAuthorityLevel.MEDIUM,
        new BigDecimal("0.90"), null));

    service.recordMemoryAccess(tenantId, record.getId());
    AiMemoryRecord accessed = service.recordMemoryAccess(tenantId, record.getId());

    assertThat(accessed.getAccessCount()).isEqualTo(2);
    assertThat(accessed.getLastAccessedAt()).isEqualTo(FIXED_CLOCK.instant());
  }

  // ----------------------------- 11. supersede -----------------------------

  @Test
  void supersedeCreatesNewVersionAndMarksPreviousSuperseded() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord v1 = service.createMemoryRecord(create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT,
        "ver", AiMemoryAuthorityLevel.MEDIUM, new BigDecimal("0.70"), null));

    AiMemoryRecord v2 = service.supersedeMemoryRecord(new SupersedeMemoryCommand(tenantId, v1.getId(),
        null, AiMemoryAuthorityLevel.HIGH, "Alias hint v2", "Refined alias mapping", "X->Z",
        new BigDecimal("0.95"), 7, null, "Operator refined", UUID.randomUUID()));

    assertThat(v2.getVersion()).isEqualTo(2);
    assertThat(v2.getStatus()).isEqualTo(AiMemoryStatus.ACTIVE);
    assertThat(service.getRecord(tenantId, v1.getId()).getStatus()).isEqualTo(AiMemoryStatus.SUPERSEDED);
    List<AiMemoryRecord> active =
        service.searchMemory(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "ver", false, false, 25);
    assertThat(active).hasSize(1);
    assertThat(active.get(0).getVersion()).isEqualTo(2);
    List<AiMemoryInvalidationEvent> events = service.listInvalidations(tenantId, v1.getId());
    assertThat(events.get(0).getReasonCode())
        .isEqualTo(AiMemoryInvalidationReasonCode.SUPERSEDED_BY_NEW_VERSION);
  }

  // ----------------------------- 12. payload sanitization -----------------------------

  @Test
  void rawPromptOrSecretLikePayloadIsRejected() {
    UUID tenantId = UUID.randomUUID();
    CreateMemoryCommand cmd = new CreateMemoryCommand(tenantId, AiMemoryNamespace.DOCUMENT_TEMPLATE,
        "leak", AiMemoryType.HINT, AiMemoryAuthorityLevel.MEDIUM, AiMemorySourceType.SYSTEM, null, null,
        "Title", "Contains OPENAI_API_KEY=sk-secret-value", null, new BigDecimal("0.90"), 1, null, null, null);

    assertThatThrownBy(() -> service.createMemoryRecord(cmd))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden");
  }

  // ----------------------------- 13. memory is never authoritative -----------------------------

  @Test
  void policyKeepsMemoryAdvisoryAndDefersToDeterministicForSensitiveNamespaces() {
    assertThat(policy.isAuthoritativeUseAllowed(AiMemoryNamespace.PAYMENT_MATCH_HINT)).isFalse();
    assertThat(policy.shouldPreferDeterministicSource(AiMemoryNamespace.PAYMENT_MATCH_HINT)).isTrue();
    assertThat(policy.shouldPreferDeterministicSource(AiMemoryNamespace.TRUST_SIGNAL_HINT)).isTrue();
    assertThat(policy.shouldPreferDeterministicSource(AiMemoryNamespace.DOCUMENT_TEMPLATE)).isFalse();

    Instant now = FIXED_CLOCK.instant();
    assertThat(policy.canUseMemory(AiMemoryNamespace.DOCUMENT_TEMPLATE, AiMemoryAuthorityLevel.MEDIUM,
        AiMemoryStatus.ACTIVE, null, new BigDecimal("0.80"), now)).isTrue();
    assertThat(policy.canUseMemory(AiMemoryNamespace.DOCUMENT_TEMPLATE, AiMemoryAuthorityLevel.MEDIUM,
        AiMemoryStatus.INVALIDATED, null, new BigDecimal("0.80"), now)).isFalse();
    assertThat(policy.canUseMemory(AiMemoryNamespace.DOCUMENT_TEMPLATE, AiMemoryAuthorityLevel.MEDIUM,
        AiMemoryStatus.ACTIVE, now.minusSeconds(1), new BigDecimal("0.80"), now)).isFalse();
  }

  // ----------------------------- 14. runtime trace metadata only -----------------------------

  @Test
  void runtimeTraceStoresMetadataAndHasNoRawPromptOrResponseField() {
    UUID tenantId = UUID.randomUUID();
    AiRuntimeTrace trace = traceService.recordRuntimeTrace(new RecordRuntimeTraceCommand(tenantId,
        "DOCUMENT_EXTRACTION", "local", "deterministic-stub", "v3", "schema-2", 120, 40,
        new BigDecimal("0.0500"), AiRuntimeStatus.FALLBACK_USED, null,
        AiMemorySourceType.DOCUMENT_TRUST_RUN, UUID.randomUUID()));

    assertThat(traceService.getRuntimeTrace(tenantId, trace.getId()).getWorkloadType())
        .isEqualTo("DOCUMENT_EXTRACTION");
    assertThat(traceService.getRuntimeTrace(tenantId, trace.getId()).getStatus())
        .isEqualTo(AiRuntimeStatus.FALLBACK_USED);
    // No raw prompt body / model response / raw text column may exist on the trace entity.
    for (Field field : AiRuntimeTrace.class.getDeclaredFields()) {
      String name = field.getName().toLowerCase(Locale.ROOT);
      assertThat(name).doesNotContain("body");
      assertThat(name).doesNotContain("response");
      assertThat(name).doesNotContain("rawprompt");
      assertThat(name).doesNotContain("message");
    }
  }

  // ----------------------------- 15. tenant isolation -----------------------------

  @Test
  void tenantCannotReadOrInvalidateAnotherTenantsRecord() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    AiMemoryRecord record = service.createMemoryRecord(create(tenantA, AiMemoryNamespace.PRODUCT_ALIAS_HINT,
        "iso", AiMemoryAuthorityLevel.MEDIUM, new BigDecimal("0.90"), null));

    assertThatThrownBy(() -> service.getRecord(tenantB, record.getId()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.invalidateMemoryRecord(tenantB, record.getId(),
        AiMemoryInvalidationReasonCode.USER_INVALIDATED, "x", AiMemoryActorType.OPERATOR, null))
        .isInstanceOf(NotFoundException.class);
    assertThat(service.getRecord(tenantA, record.getId()).getStatus()).isEqualTo(AiMemoryStatus.ACTIVE);
  }

  // ----------------------------- expireDueRecords sweep -----------------------------

  @Test
  void expireDueRecordsSweepsPastTtlActiveRecords() {
    UUID tenantId = UUID.randomUUID();
    service.createMemoryRecord(create(tenantId, AiMemoryNamespace.DOCUMENT_TEMPLATE, "sweep",
        AiMemoryAuthorityLevel.MEDIUM, new BigDecimal("0.90"), 600L));

    int expired = service.expireDueRecords(tenantId, LATER_CLOCK.instant());

    assertThat(expired).isEqualTo(1);
    assertThat(service.searchMemory(tenantId, AiMemoryNamespace.DOCUMENT_TEMPLATE, null, true, false, 25))
        .isEmpty(); // status is now EXPIRED, not ACTIVE, so it is no longer in the active search set
  }
}
