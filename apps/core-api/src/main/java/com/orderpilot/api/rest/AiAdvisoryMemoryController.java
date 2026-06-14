package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryRetrievalRequest;
import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryRetrievalResponse;
import com.orderpilot.application.services.trust.AiAdvisoryMemoryRetrievalService;
import com.orderpilot.application.services.trust.AiAdvisoryMemoryRetrievalService.RetrievalCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-19 Layer B — Advisory AI Memory Retrieval surface.
 *
 * Tenant-scoped advisory retrieval under {@code /api/v1/trust/ai-memory/advisory-retrieval}. Although it is
 * a POST (it carries a structured query body), it is a pure read: it requires {@code TRUST_AI_MEMORY_READ}
 * (see {@code ApiPermissionInterceptor}) — never the write/invalidate permissions. Tenant is resolved from
 * context; the request never carries a tenant id. Responses contain only sanitized, advisory hints.
 */
@RestController
public class AiAdvisoryMemoryController {
  private final AiAdvisoryMemoryRetrievalService retrievalService;

  public AiAdvisoryMemoryController(AiAdvisoryMemoryRetrievalService retrievalService) {
    this.retrievalService = retrievalService;
  }

  @PostMapping("/api/v1/trust/ai-memory/advisory-retrieval")
  public AdvisoryMemoryRetrievalResponse retrieve(@RequestBody AdvisoryMemoryRetrievalRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    return retrievalService.retrieve(new RetrievalCommand(
        tenantId,
        parseEnum(AiAdvisoryTaskType.class, request.taskType(), "taskType"),
        parseEnums(AiMemoryNamespace.class, request.namespaces(), "namespace"),
        parseEnums(AiMemorySourceType.class, request.sourceTypes(), "sourceType"),
        request.subjectType(),
        request.subjectId(),
        request.lookupKey(),
        request.maxResults(),
        request.minConfidence(),
        Boolean.TRUE.equals(request.includeSuperseded()),
        Boolean.TRUE.equals(request.includeInvalidated())));
  }

  private static <E extends Enum<E>> List<E> parseEnums(Class<E> type, List<String> values, String field) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream().filter(v -> v != null && !v.isBlank())
        .map(v -> parseEnum(type, v, field)).toList();
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
}
