package com.orderpilot.application.services.trust;

import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiRuntimeStatus;
import com.orderpilot.domain.trust.ai.AiRuntimeTrace;
import com.orderpilot.domain.trust.ai.AiRuntimeTraceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance — runtime trace metadata service.
 *
 * Records and serves SAFE metadata about AI/runtime workloads only: provider/model/prompt-version/token
 * and cost estimates, outcome status, and a bounded source pointer. It deliberately never accepts or
 * stores a raw prompt body, raw model response, secrets, or customer message/document text (the entity
 * has no such column). Provider-agnostic: this stage never calls an external AI provider. All queries are
 * tenant-scoped and bounded.
 */
@Service
public class AiRuntimeTraceService {
  public static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 100;
  static final int COST_SCALE = 4;

  private final AiRuntimeTraceRepository traces;
  private Clock clock;

  public AiRuntimeTraceService(AiRuntimeTraceRepository traces, Clock clock) {
    this.traces = traces;
    this.clock = clock;
  }

  public record RecordRuntimeTraceCommand(
      UUID tenantId, String workloadType, String modelProvider, String modelName, String promptVersion,
      String schemaVersion, Integer inputTokenEstimate, Integer outputTokenEstimate, BigDecimal costUnits,
      AiRuntimeStatus status, String failureCode, AiMemorySourceType sourceType, UUID sourceId) {}

  @Transactional
  public AiRuntimeTrace recordRuntimeTrace(RecordRuntimeTraceCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    String workloadType = requireText(cmd.workloadType(), "workloadType");
    String promptVersion = requireText(cmd.promptVersion(), "promptVersion");
    AiRuntimeStatus status = required(cmd.status(), "status");
    Integer inputTokens = nonNegativeOrNull(cmd.inputTokenEstimate(), "inputTokenEstimate");
    Integer outputTokens = nonNegativeOrNull(cmd.outputTokenEstimate(), "outputTokenEstimate");
    BigDecimal costUnits = normalizeCost(cmd.costUnits());

    return traces.save(new AiRuntimeTrace(tenantId, workloadType, bound(cmd.modelProvider(), 48),
        bound(cmd.modelName(), 80), promptVersion, bound(cmd.schemaVersion(), 48), inputTokens, outputTokens,
        costUnits, status, bound(cmd.failureCode(), 48), cmd.sourceType(), cmd.sourceId(), clock.instant()));
  }

  @Transactional(readOnly = true)
  public AiRuntimeTrace getRuntimeTrace(UUID tenantId, UUID id) {
    return traces.findByIdAndTenantId(required(id, "id"), required(tenantId, "tenantId"))
        .orElseThrow(() -> new NotFoundException("AI runtime trace not found"));
  }

  @Transactional(readOnly = true)
  public List<AiRuntimeTrace> listRuntimeTraces(UUID tenantId, String workloadType, AiRuntimeStatus status,
      AiMemorySourceType sourceType, UUID sourceId, int page, int size) {
    required(tenantId, "tenantId");
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    String workload = trimToNull(workloadType);
    if (sourceType != null && sourceId != null) {
      return traces.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(
          tenantId, sourceType, sourceId, pageable);
    }
    if (workload != null) {
      return traces.findByTenantIdAndWorkloadTypeOrderByCreatedAtDesc(tenantId, workload, pageable);
    }
    if (status != null) {
      return traces.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status, pageable);
    }
    return traces.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
  }

  // ----------------------------- helpers -----------------------------

  private static BigDecimal normalizeCost(BigDecimal value) {
    if (value == null) {
      return null;
    }
    BigDecimal scaled = value.setScale(COST_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() < 0) {
      throw new IllegalArgumentException("costUnits must not be negative");
    }
    return scaled;
  }

  private static Integer nonNegativeOrNull(Integer value, String field) {
    if (value == null) {
      return null;
    }
    if (value < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
    return value;
  }

  static int clampLimit(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String requireText(String value, String field) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return trimmed;
  }

  private static String bound(String value, int max) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
