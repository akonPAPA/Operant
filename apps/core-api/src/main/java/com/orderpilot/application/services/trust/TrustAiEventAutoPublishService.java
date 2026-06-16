package com.orderpilot.application.services.trust;

import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.events.TrustAiEventType;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-19 Layer A — Transactional Trust/AI Event Auto-Publishing Hooks.
 *
 * Thin, deterministic adapter that lets existing OP-CAP-17A–17F command/application services emit a
 * bounded {@link TrustAiDomainEvent} after a safe state transition has been persisted. It builds a
 * deterministic, tenant-safe idempotency key per source record (never a random UUID), sanitizes/bounds
 * the payload summary, and delegates to {@link TrustAiEventPublisherService#publishOnce} — which is
 * idempotent per (tenant, idempotency key). A duplicate publish (including a concurrent insert race) is
 * treated as idempotent success, so a hook never duplicates an event and never corrupts the primary
 * business transaction.
 *
 * <p>This adapter only ever <em>publishes</em>. It never processes events, never derives AI memory, and
 * is never invoked by the OP-CAP-18 projector — so it cannot create an event &rarr; projector &rarr; event
 * cycle. The {@code AI_MEMORY_INVALIDATED}/{@code AI_RUNTIME_TRACE_RECORDED} hooks exist for completeness
 * but are intentionally <em>not</em> auto-wired into the memory/runtime services (see STAGE_19 doc) to
 * keep the loop acyclic. Memory remains advisory and low-authority; deterministic backend services remain
 * the source of truth.
 */
@Service
public class TrustAiEventAutoPublishService {
  static final int MAX_SUMMARY = 512;

  private final TrustAiEventPublisherService publisher;

  public TrustAiEventAutoPublishService(TrustAiEventPublisherService publisher) {
    this.publisher = publisher;
  }

  // ----------------------------- 17A document trust -----------------------------

  /** Publish {@code DOCUMENT_TRUST_COMPLETED} after a document trust run is persisted. */
  public Optional<TrustAiDomainEvent> publishDocumentTrustCompleted(
      UUID tenantId, UUID documentTrustRunId, String summary) {
    return publish(tenantId, TrustAiEventType.DOCUMENT_TRUST_COMPLETED, AiMemorySourceType.DOCUMENT_TRUST_RUN,
        documentTrustRunId, key("document-trust-completed", documentTrustRunId), summary);
  }

  // ----------------------------- 17B counterparty trust -----------------------------

  /**
   * Publish {@code COUNTERPARTY_TRUST_UPDATED} after a counterparty profile/scoring update. The profile
   * version (or any monotonic discriminator) is part of the key so each distinct update publishes once.
   */
  public Optional<TrustAiDomainEvent> publishCounterpartyTrustUpdated(
      UUID tenantId, UUID counterpartyProfileId, long version, String summary) {
    return publish(tenantId, TrustAiEventType.COUNTERPARTY_TRUST_UPDATED, AiMemorySourceType.COUNTERPARTY_PROFILE,
        counterpartyProfileId, key("counterparty-trust-updated", counterpartyProfileId) + ":" + version, summary);
  }

  // ----------------------------- 17C payment intelligence -----------------------------

  /** Publish {@code PAYMENT_OBLIGATION_UPDATED} after an obligation state change is persisted. */
  public Optional<TrustAiDomainEvent> publishPaymentObligationUpdated(
      UUID tenantId, UUID paymentObligationId, long version, String summary) {
    return publish(tenantId, TrustAiEventType.PAYMENT_OBLIGATION_UPDATED, AiMemorySourceType.PAYMENT_OBLIGATION,
        paymentObligationId, key("payment-obligation-updated", paymentObligationId) + ":" + version, summary);
  }

  /** Publish {@code PAYMENT_ALLOCATION_RECORDED} after an allocation is persisted (one event per allocation). */
  public Optional<TrustAiDomainEvent> publishPaymentAllocationRecorded(
      UUID tenantId, UUID paymentAllocationId, String summary) {
    return publish(tenantId, TrustAiEventType.PAYMENT_ALLOCATION_RECORDED, AiMemorySourceType.PAYMENT_OBLIGATION,
        paymentAllocationId, key("payment-allocation-recorded", paymentAllocationId), summary);
  }

  // ----------------------------- 17D trust risk decision -----------------------------

  /** Publish {@code TRUST_RISK_DECIDED} after a deterministic risk decision is persisted. */
  public Optional<TrustAiDomainEvent> publishTrustRiskDecided(
      UUID tenantId, UUID trustRiskDecisionId, String summary) {
    return publish(tenantId, TrustAiEventType.TRUST_RISK_DECIDED, AiMemorySourceType.TRUST_RISK_DECISION,
        trustRiskDecisionId, key("trust-risk-decided", trustRiskDecisionId), summary);
  }

  /**
   * Publish {@code TRUST_RISK_OVERRIDDEN} after a manual override is persisted. The override-record id is
   * part of the key so a decision overridden more than once publishes a distinct event per override.
   */
  public Optional<TrustAiDomainEvent> publishTrustRiskOverridden(
      UUID tenantId, UUID trustRiskDecisionId, UUID overrideId, String summary) {
    return publish(tenantId, TrustAiEventType.TRUST_RISK_OVERRIDDEN, AiMemorySourceType.TRUST_RISK_DECISION,
        trustRiskDecisionId, key("trust-risk-overridden", trustRiskDecisionId) + ":" + overrideId, summary);
  }

  // ----------------------------- 17F memory / runtime (not auto-wired; see class doc) -----------------------------

  /** Publish {@code AI_MEMORY_INVALIDATED}. NOT auto-wired — kept acyclic; for explicit safe callers only. */
  public Optional<TrustAiDomainEvent> publishAiMemoryInvalidated(
      UUID tenantId, UUID aiMemoryRecordId, String summary) {
    return publish(tenantId, TrustAiEventType.AI_MEMORY_INVALIDATED, AiMemorySourceType.SYSTEM,
        aiMemoryRecordId, key("ai-memory-invalidated", aiMemoryRecordId), summary);
  }

  /** Publish {@code AI_RUNTIME_TRACE_RECORDED}. NOT auto-wired — avoids unbounded recursive trace fan-out. */
  public Optional<TrustAiDomainEvent> publishRuntimeTraceRecorded(
      UUID tenantId, UUID runtimeTraceId, String summary) {
    return publish(tenantId, TrustAiEventType.AI_RUNTIME_TRACE_RECORDED, AiMemorySourceType.SYSTEM,
        runtimeTraceId, key("runtime-trace-recorded", runtimeTraceId), summary);
  }

  // ----------------------------- internals -----------------------------

  private Optional<TrustAiDomainEvent> publish(UUID tenantId, TrustAiEventType eventType,
      AiMemorySourceType sourceType, UUID sourceId, String idempotencyKey, String summary) {
    if (tenantId == null || sourceId == null) {
      // Defensive: a hook with no authoritative source id is a no-op rather than a failure that could
      // roll back the surrounding business transaction.
      return Optional.empty();
    }
    try {
      return Optional.of(publisher.publishOnce(
          tenantId, eventType, sourceType, sourceId, idempotencyKey, sanitize(summary)));
    } catch (DataIntegrityViolationException duplicate) {
      // Concurrent insert race on the same (tenant, idempotency key): the event already exists, so this
      // is an idempotent success — never propagate it into the business transaction.
      return Optional.empty();
    }
  }

  private static String key(String prefix, UUID sourceId) {
    return prefix + ":" + sourceId;
  }

  /**
   * Bounded, conservative sanitization for an internal, already-safe summary. Strips control characters and
   * collapses whitespace (so a stack trace / multi-line blob can never become a payload), then truncates to
   * {@link #MAX_SUMMARY}. The publisher bounds again; this keeps the summary clean at the source.
   */
  static String sanitize(String summary) {
    if (summary == null) {
      return null;
    }
    String collapsed = summary.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").strip();
    if (collapsed.isEmpty()) {
      return null;
    }
    String lower = collapsed.toLowerCase(Locale.ROOT);
    if (lower.startsWith("exception") || lower.contains("\tat ") || lower.contains(".java:")) {
      // Never publish a raw exception/stack-trace-shaped string as a summary.
      return null;
    }
    return collapsed.length() <= MAX_SUMMARY ? collapsed : collapsed.substring(0, MAX_SUMMARY);
  }
}
