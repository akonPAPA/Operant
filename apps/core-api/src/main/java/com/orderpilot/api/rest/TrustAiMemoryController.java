package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AiMemoryDtos.AiMemoryEvidenceRefDto;
import com.orderpilot.api.dto.AiMemoryDtos.AiMemoryEvidenceRefRequest;
import com.orderpilot.api.dto.AiMemoryDtos.AiMemoryInvalidationEventDto;
import com.orderpilot.api.dto.AiMemoryDtos.AiMemoryRecordDto;
import com.orderpilot.api.dto.AiMemoryDtos.AiRuntimeTraceDto;
import com.orderpilot.api.dto.AiMemoryDtos.CreateAiMemoryRecordRequest;
import com.orderpilot.api.dto.AiMemoryDtos.InvalidateAiMemoryRecordRequest;
import com.orderpilot.api.dto.AiMemoryDtos.RecordAiRuntimeTraceRequest;
import com.orderpilot.api.dto.AiMemoryDtos.SearchAiMemoryResponse;
import com.orderpilot.api.dto.AiMemoryDtos.SupersedeAiMemoryRecordRequest;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.CreateMemoryCommand;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.EvidenceSpec;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.SupersedeMemoryCommand;
import com.orderpilot.application.services.trust.AiRuntimeTraceService;
import com.orderpilot.application.services.trust.AiRuntimeTraceService.RecordRuntimeTraceCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.trust.ai.AiMemoryActorType;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryEvidenceRef;
import com.orderpilot.domain.trust.ai.AiMemoryEvidenceType;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationEvent;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationReasonCode;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryType;
import com.orderpilot.domain.trust.ai.AiRuntimeStatus;
import com.orderpilot.domain.trust.ai.AiRuntimeTrace;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Tenant-scoped AI memory + runtime-trace surface under {@code /api/v1/trust}. Reads require
 * {@code TRUST_AI_MEMORY_READ}; create/supersede require {@code TRUST_AI_MEMORY_WRITE}; invalidate
 * requires {@code TRUST_AI_MEMORY_INVALIDATE}; runtime-trace read/write use the dedicated
 * {@code TRUST_AI_RUNTIME_TRACE_READ}/{@code TRUST_AI_RUNTIME_TRACE_WRITE} permissions (see
 * {@code ApiPermissionInterceptor}). Tenant is resolved from context; ids are never trusted across
 * tenants. Memory is advisory and low-authority — never source of truth. No raw documents/prompts/secrets
 * are accepted or returned.
 */
@RestController
public class TrustAiMemoryController {
  private final AiMemoryGovernanceService memoryService;
  private final AiRuntimeTraceService runtimeTraceService;

  public TrustAiMemoryController(
      AiMemoryGovernanceService memoryService, AiRuntimeTraceService runtimeTraceService) {
    this.memoryService = memoryService;
    this.runtimeTraceService = runtimeTraceService;
  }

  // ----------------------------- ai memory -----------------------------

  @PostMapping("/api/v1/trust/ai-memory")
  public AiMemoryRecordDto create(@RequestBody CreateAiMemoryRecordRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    List<EvidenceSpec> evidence = request.evidence() == null ? List.of()
        : request.evidence().stream().map(TrustAiMemoryController::toEvidenceSpec).toList();
    AiMemoryRecord record = memoryService.createMemoryRecord(new CreateMemoryCommand(
        tenantId,
        parseEnum(AiMemoryNamespace.class, request.namespace(), "namespace"),
        request.memoryKey(),
        parseEnum(AiMemoryType.class, request.memoryType(), "memoryType"),
        parseEnum(AiMemoryAuthorityLevel.class, request.authorityLevel(), "authorityLevel"),
        parseEnum(AiMemorySourceType.class, request.sourceType(), "sourceType"),
        request.sourceId(),
        request.sourceRef(),
        request.title(),
        request.summary(),
        request.normalizedValue(),
        request.confidence(),
        request.weight(),
        request.ttlSeconds(),
        evidence,
        null));
    return toDto(record);
  }

  @PostMapping("/api/v1/trust/ai-memory/{id}/supersede")
  public AiMemoryRecordDto supersede(
      @PathVariable UUID id, @RequestBody SupersedeAiMemoryRecordRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    AiMemoryRecord record = memoryService.supersedeMemoryRecord(new SupersedeMemoryCommand(
        tenantId,
        id,
        parseEnumOrNull(AiMemoryType.class, request.memoryType()),
        parseEnumOrNull(AiMemoryAuthorityLevel.class, request.authorityLevel()),
        request.title(),
        request.summary(),
        request.normalizedValue(),
        request.confidence(),
        request.weight(),
        request.ttlSeconds(),
        request.reason(),
        null));
    return toDto(record);
  }

  @PostMapping("/api/v1/trust/ai-memory/{id}/invalidate")
  public AiMemoryRecordDto invalidate(
      @PathVariable UUID id, @RequestBody InvalidateAiMemoryRecordRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    AiMemoryRecord record = memoryService.invalidateMemoryRecord(tenantId, id,
        parseEnum(AiMemoryInvalidationReasonCode.class, request.reasonCode(), "reasonCode"),
        request.reason(), AiMemoryActorType.OPERATOR, null);
    return toDto(record);
  }

  @GetMapping("/api/v1/trust/ai-memory/{id}")
  public AiMemoryRecordDto get(@PathVariable UUID id) {
    return toDto(memoryService.getRecord(TenantContext.requireTenantId(), id));
  }

  @GetMapping("/api/v1/trust/ai-memory")
  public SearchAiMemoryResponse search(
      @RequestParam(name = "namespace") String namespace,
      @RequestParam(name = "memoryKey", required = false) String memoryKey,
      @RequestParam(name = "includeExpired", defaultValue = "false") boolean includeExpired,
      @RequestParam(name = "includeLowConfidence", defaultValue = "false") boolean includeLowConfidence,
      @RequestParam(name = "limit", defaultValue = "25") int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    AiMemoryNamespace ns = parseEnum(AiMemoryNamespace.class, namespace, "namespace");
    List<AiMemoryRecordDto> records = memoryService
        .searchMemory(tenantId, ns, memoryKey, includeExpired, includeLowConfidence, limit)
        .stream().map(TrustAiMemoryController::toDto).toList();
    return new SearchAiMemoryResponse(ns.name(), records.size(), includeExpired, includeLowConfidence, records);
  }

  @GetMapping("/api/v1/trust/ai-memory/{id}/evidence")
  public List<AiMemoryEvidenceRefDto> evidence(@PathVariable UUID id) {
    return memoryService.listEvidence(TenantContext.requireTenantId(), id)
        .stream().map(TrustAiMemoryController::toDto).toList();
  }

  @GetMapping("/api/v1/trust/ai-memory/{id}/invalidations")
  public List<AiMemoryInvalidationEventDto> invalidations(@PathVariable UUID id) {
    return memoryService.listInvalidations(TenantContext.requireTenantId(), id)
        .stream().map(TrustAiMemoryController::toDto).toList();
  }

  // ----------------------------- ai runtime traces -----------------------------

  @PostMapping("/api/v1/trust/ai-runtime/traces")
  public AiRuntimeTraceDto recordTrace(@RequestBody RecordAiRuntimeTraceRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    AiRuntimeTrace trace = runtimeTraceService.recordRuntimeTrace(new RecordRuntimeTraceCommand(
        tenantId,
        request.workloadType(),
        request.modelProvider(),
        request.modelName(),
        request.promptVersion(),
        request.schemaVersion(),
        request.inputTokenEstimate(),
        request.outputTokenEstimate(),
        request.costUnits(),
        parseEnum(AiRuntimeStatus.class, request.status(), "status"),
        request.failureCode(),
        parseEnumOrNull(AiMemorySourceType.class, request.sourceType()),
        request.sourceId()));
    return toDto(trace);
  }

  @GetMapping("/api/v1/trust/ai-runtime/traces")
  public List<AiRuntimeTraceDto> listTraces(
      @RequestParam(name = "workloadType", required = false) String workloadType,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "sourceType", required = false) String sourceType,
      @RequestParam(name = "sourceId", required = false) UUID sourceId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    UUID tenantId = TenantContext.requireTenantId();
    return runtimeTraceService.listRuntimeTraces(tenantId, workloadType,
        parseEnumOrNull(AiRuntimeStatus.class, status),
        parseEnumOrNull(AiMemorySourceType.class, sourceType), sourceId, page, size)
        .stream().map(TrustAiMemoryController::toDto).toList();
  }

  // ----------------------------- mappers -----------------------------

  private static EvidenceSpec toEvidenceSpec(AiMemoryEvidenceRefRequest r) {
    return new EvidenceSpec(
        parseEnum(AiMemoryEvidenceType.class, r.evidenceType(), "evidenceType"),
        r.evidenceRef(),
        parseEnumOrNull(AiMemorySourceType.class, r.sourceType()),
        r.sourceId(),
        r.fieldKey(),
        r.confidence());
  }

  private static AiMemoryRecordDto toDto(AiMemoryRecord r) {
    return new AiMemoryRecordDto(
        r.getId(), r.getNamespace().name(), r.getMemoryKey(), r.getMemoryType().name(), r.getStatus().name(),
        r.getAuthorityLevel().name(), r.getSourceType().name(), r.getSourceId(), r.getSourceRef(),
        r.getTitle(), r.getSummary(), r.getNormalizedValue(), r.getConfidence(), r.getWeight(),
        r.getVersion(), r.getExpiresAt(), r.getInvalidatedAt(), r.getInvalidationReason(),
        r.getCreatedAt(), r.getUpdatedAt(), r.getLastAccessedAt(), r.getAccessCount());
  }

  private static AiMemoryEvidenceRefDto toDto(AiMemoryEvidenceRef r) {
    return new AiMemoryEvidenceRefDto(
        r.getId(), r.getAiMemoryRecordId(), r.getEvidenceType().name(), r.getEvidenceRef(),
        r.getSourceType() == null ? null : r.getSourceType().name(), r.getSourceId(), r.getFieldKey(),
        r.getConfidence(), r.getCreatedAt());
  }

  private static AiMemoryInvalidationEventDto toDto(AiMemoryInvalidationEvent e) {
    return new AiMemoryInvalidationEventDto(
        e.getId(), e.getAiMemoryRecordId(), e.getPreviousStatus().name(), e.getNewStatus().name(),
        e.getReasonCode().name(), e.getReason(), e.getCreatedAt());
  }

  private static AiRuntimeTraceDto toDto(AiRuntimeTrace t) {
    return new AiRuntimeTraceDto(
        t.getId(), t.getWorkloadType(), t.getModelProvider(), t.getModelName(), t.getPromptVersion(),
        t.getSchemaVersion(), t.getInputTokenEstimate(), t.getOutputTokenEstimate(), t.getCostUnits(),
        t.getStatus().name(), t.getFailureCode(), t.getSourceType() == null ? null : t.getSourceType().name(),
        t.getSourceId(), t.getCreatedAt());
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown " + field + ": " + value);
    }
  }

  private static <E extends Enum<E>> E parseEnumOrNull(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown value for " + type.getSimpleName() + ": " + value);
    }
  }
}
