package com.orderpilot.api.dto;

import com.orderpilot.domain.intake.ProcessingJob;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-29 — safe, minimal worker lease contract. A claimed lease carries only what the out-of-process
 * worker needs to fetch and process the source ({@code jobId}, {@code jobType}, {@code targetType},
 * {@code targetId}) plus bounded operational fields ({@code attempt}, {@code status}, {@code leasedAt}).
 *
 * <p>It intentionally never carries the tenant id, credentials, quota/usage state, runtime-control
 * decision detail, raw business data, provider payload, prompt or extracted text. The tenant is resolved
 * server-side from {@code TenantContext}; the worker authenticates with the internal worker permission.
 */
public final class WorkerJobLeaseDtos {
  private WorkerJobLeaseDtos() {}

  public record WorkerJobLease(
      UUID jobId, String jobType, String targetType, UUID targetId, int attempt, String status, Instant leasedAt) {
    public static WorkerJobLease from(ProcessingJob job) {
      return new WorkerJobLease(job.getId(), job.getJobType(), job.getTargetType(), job.getTargetId(),
          job.getAttempts(), job.getStatus(), job.getStartedAt());
    }
  }

  public record WorkerJobClaimResponse(int claimed, List<WorkerJobLease> jobs) {}
}
