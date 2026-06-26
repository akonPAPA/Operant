package com.orderpilot.application.services.support;

import com.orderpilot.api.dto.SupportInternalDtos.SupportTenantDiagnosticsResponse;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.intake.ProcessingJobStatus;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-51 — builds a bounded, redacted, READ-ONLY diagnostics summary for one tenant. It exposes only
 * high-level health and existing safe aggregates (processing-job status counts and the last activity
 * timestamp). It deliberately exposes NO raw payloads, NO secrets, NO connector credentials, NO raw
 * documents, and NO customer PII. It never mutates any business row.
 *
 * <p>Authorization (staff principal + active, tenant-scoped, unexpired grant) is performed by
 * {@link SupportAccessService#authorize} before this runs, so a denied request never reaches here.
 */
@Service
public class SupportDiagnosticsService {
  private final ProcessingJobRepository processingJobRepository;
  private final Clock clock;

  public SupportDiagnosticsService(ProcessingJobRepository processingJobRepository, Clock clock) {
    this.processingJobRepository = processingJobRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public SupportTenantDiagnosticsResponse diagnose(UUID tenantId) {
    Map<String, Long> jobStatusCounts = new LinkedHashMap<>();
    long total = 0L;
    long failed = 0L;
    for (ProcessingJobStatus status : ProcessingJobStatus.values()) {
      long count = processingJobRepository.countByTenantIdAndStatus(tenantId, status.name());
      jobStatusCounts.put(status.name(), count);
      total += count;
      if (status == ProcessingJobStatus.FAILED || status == ProcessingJobStatus.REJECTED) {
        failed += count;
      }
    }
    java.time.Instant lastActivity = processingJobRepository.findFirstByTenantIdOrderByQueuedAtDesc(tenantId)
        .map(ProcessingJob::getQueuedAt)
        .orElse(null);

    String health;
    if (total == 0L) {
      health = "NO_RECENT_ACTIVITY";
    } else if (failed > 0L) {
      health = "ATTENTION";
    } else {
      health = "HEALTHY";
    }

    return new SupportTenantDiagnosticsResponse(
        tenantId,
        health,
        jobStatusCounts,
        total,
        lastActivity,
        clock.instant(),
        "DISABLED",
        "DIAGNOSTICS");
  }
}
