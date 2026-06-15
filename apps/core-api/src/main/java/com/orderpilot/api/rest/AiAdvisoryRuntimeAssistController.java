package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AiAdvisoryRuntimeAssistDtos.RuntimeAssistResponse;
import com.orderpilot.application.services.trust.AiAdvisoryRuntimeAssistService;
import com.orderpilot.application.services.trust.AiAdvisoryRuntimeAssistService.AssistCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.RuntimeAssistContextType;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-20 Layer A — AI Advisory Runtime Assist surface.
 *
 * Read-only tenant-scoped runtime assist under {@code /api/v1/trust/ai-memory/advisory-assist}. As a GET
 * under {@code /api/v1/trust/ai-memory} it requires {@code TRUST_AI_MEMORY_READ} (see
 * {@code ApiPermissionInterceptor}) — the same read permission as advisory retrieval, never a write
 * permission. Tenant is resolved from context; the request never carries a tenant id. The endpoint never
 * mutates memory or business state and returns only sanitized, bounded, advisory hints.
 */
@RestController
public class AiAdvisoryRuntimeAssistController {
  private final AiAdvisoryRuntimeAssistService assistService;

  public AiAdvisoryRuntimeAssistController(AiAdvisoryRuntimeAssistService assistService) {
    this.assistService = assistService;
  }

  @GetMapping("/api/v1/trust/ai-memory/advisory-assist")
  public RuntimeAssistResponse assist(
      @RequestParam(name = "contextType", defaultValue = "TRUST_VALIDATION_REVIEW") String contextType,
      @RequestParam(name = "contextId", required = false) UUID contextId,
      @RequestParam(name = "taskType", required = false) String taskType,
      @RequestParam(name = "lookupKey", required = false) String lookupKey,
      @RequestParam(name = "maxHints", required = false) Integer maxHints) {
    UUID tenantId = TenantContext.requireTenantId();
    return assistService.assist(new AssistCommand(
        tenantId,
        parseEnum(RuntimeAssistContextType.class, contextType, "contextType"),
        contextId,
        parseEnumOrNull(AiAdvisoryTaskType.class, taskType),
        lookupKey,
        maxHints));
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
