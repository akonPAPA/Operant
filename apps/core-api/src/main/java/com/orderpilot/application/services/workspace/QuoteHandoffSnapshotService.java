package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage11EDtos.QuoteHandoffCommand;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.OutboxEvent;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.domain.workspace.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteHandoffSnapshotService {
  private static final String PAYLOAD_VERSION = "quote-handoff-v1";

  private final DraftQuoteLineRepository lineRepository;
  private final QuoteValidationIssueRepository issueRepository;
  private final QuoteHandoffSnapshotRepository snapshotRepository;
  private final QuoteHandoffReadinessService readinessService;
  private final JsonSupport jsonSupport;
  private final AuditEventService auditEventService;
  private final OutboxEventRepository outboxEventRepository;
  private final Clock clock;

  public QuoteHandoffSnapshotService(DraftQuoteLineRepository lineRepository, QuoteValidationIssueRepository issueRepository, QuoteHandoffSnapshotRepository snapshotRepository, QuoteHandoffReadinessService readinessService, JsonSupport jsonSupport, AuditEventService auditEventService, OutboxEventRepository outboxEventRepository, Clock clock) {
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.snapshotRepository = snapshotRepository;
    this.readinessService = readinessService;
    this.jsonSupport = jsonSupport;
    this.auditEventService = auditEventService;
    this.outboxEventRepository = outboxEventRepository;
    this.clock = clock;
  }

  @Transactional
  public QuoteHandoffSnapshot prepare(UUID quoteId, QuoteHandoffCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = readinessService.getQuote(tenantId, quoteId);
    try {
      readinessService.requireReady(tenantId, quote);
    } catch (QuoteHandoffViolation ex) {
      auditEventService.record("QUOTE_HANDOFF_BLOCKED", "DRAFT_QUOTE", quote.getId().toString(), command == null ? null : command.actorId(), QuoteHandoffReadinessService.metadata(tenantId, quote, null, null, "HANDOFF_BLOCKED", ex.getMessage(), null, null));
      throw ex;
    }

    List<DraftQuoteLine> lines = lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId());
    List<QuoteValidationIssue> issues = issueRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quote.getId());
    int nextVersion = snapshotRepository.findTopByTenantIdAndDraftQuoteIdOrderByPayloadVersionDesc(tenantId, quote.getId()).map(existing -> existing.getPayloadVersion() + 1).orElse(1);
    Instant generatedAt = clock.instant();
    Map<String, Object> payload = payload(tenantId, quote, lines, issues, nextVersion, command == null ? null : command.actorId(), generatedAt);
    String payloadJson = jsonSupport.writeObject(payload);
    String payloadHash = sha256(payloadJson);
    Optional<QuoteHandoffSnapshot> existing = snapshotRepository.findByTenantIdAndDraftQuoteIdAndPayloadHash(tenantId, quote.getId(), payloadHash);
    if (existing.isPresent()) {
      return existing.get();
    }
    String idempotencyKey = "quote-handoff:" + sha256(tenantId + ":" + quote.getId() + ":" + payloadHash);
    QuoteHandoffSnapshot snapshot = snapshotRepository.save(new QuoteHandoffSnapshot(tenantId, quote.getId(), "HANDOFF_PREPARED", nextVersion, payloadJson, payloadHash, idempotencyKey, command == null ? null : command.actorId(), generatedAt));
    auditEventService.record("QUOTE_HANDOFF_PREPARED", "DRAFT_QUOTE", quote.getId().toString(), command == null ? null : command.actorId(), QuoteHandoffReadinessService.metadata(tenantId, quote, snapshot.getId(), null, "HANDOFF_PREPARED", command == null ? null : command.reason(), payloadHash, idempotencyKey));
    outboxEventRepository.save(new OutboxEvent(tenantId, "QUOTE_HANDOFF_SNAPSHOT", snapshot.getId(), "QUOTE_HANDOFF_PREPARED_INTERNAL", "{\"quoteId\":\"" + quote.getId() + "\",\"snapshotId\":\"" + snapshot.getId() + "\",\"externalExecution\":\"DISABLED\"}", generatedAt));
    return snapshot;
  }

  @Transactional(readOnly = true)
  public QuoteHandoffSnapshot get(UUID snapshotId) {
    return snapshotRepository.findByIdAndTenantId(snapshotId, TenantContext.requireTenantId()).orElseThrow(() -> new com.orderpilot.common.errors.NotFoundException("Quote handoff snapshot not found: " + snapshotId));
  }

  private static Map<String, Object> payload(UUID tenantId, DraftQuote quote, List<DraftQuoteLine> lines, List<QuoteValidationIssue> issues, int version, UUID actorId, Instant generatedAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("payloadVersion", PAYLOAD_VERSION);
    payload.put("payloadSequence", version);
    payload.put("tenantId", tenantId);
    payload.put("quoteId", quote.getId());
    payload.put("customerAccountId", quote.getCustomerAccountId());
    payload.put("customerDisplayName", quote.getCustomerDisplayName());
    payload.put("quoteStatus", quote.getStatus());
    payload.put("currency", quote.getCurrency());
    payload.put("subtotalAmount", quote.getSubtotalAmount());
    payload.put("discountAmount", quote.getDiscountAmount());
    payload.put("totalAmount", quote.getTotalAmount());
    Map<String, Object> approval = new LinkedHashMap<>();
    approval.put("approvedBy", quote.getApprovedBy());
    approval.put("approvedAt", quote.getApprovedAt());
    payload.put("approvalSummary", approval);
    payload.put("generatedAt", generatedAt);
    payload.put("generatedBy", actorId);
    payload.put("lines", lines.stream().sorted(Comparator.comparingInt(DraftQuoteLine::getLineNumber)).map(QuoteHandoffSnapshotService::linePayload).toList());
    payload.put("validationIssueSummary", issues.stream().map(QuoteHandoffSnapshotService::issuePayload).toList());
    payload.put("externalExecution", "DISABLED_STAGE_11E");
    return payload;
  }

  private static Map<String, Object> linePayload(DraftQuoteLine line) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("lineId", line.getId());
    value.put("lineNumber", line.getLineNumber());
    value.put("originalProductId", line.getProductId());
    value.put("selectedProductId", line.getSelectedSubstituteProductId() == null ? line.getProductId() : line.getSelectedSubstituteProductId());
    value.put("selectedSubstituteProductId", line.getSelectedSubstituteProductId());
    value.put("substituteDecisionStatus", line.getSubstituteDecisionStatus());
    value.put("substituteDecisionReasonCode", line.getSubstituteDecisionReasonCode());
    value.put("rawSku", line.getRawSku());
    value.put("normalizedSku", line.getNormalizedSku());
    value.put("productName", line.getProductName());
    value.put("quantity", line.getQuantity());
    value.put("uom", line.getUom());
    value.put("unitPrice", line.getUnitPrice());
    value.put("discountPercent", line.getDiscountPercent());
    value.put("marginPercent", line.getMarginPercent());
    value.put("lineTotal", line.getLineTotal());
    return value;
  }

  private static Map<String, Object> issuePayload(QuoteValidationIssue issue) {
    return Map.of("issueCode", issue.getIssueCode(), "severity", issue.getSeverity(), "blocking", issue.isBlocking(), "status", issue.getStatus());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
