package com.orderpilot.application.services;

import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessingJobService {
  // OP-CAP-28: bounded list page sizing. Default keeps the list cheap; the max caps any caller-supplied
  // limit so a malicious/large value can never trigger a full-table scan.
  static final int DEFAULT_LIST_LIMIT = 25;
  static final int MAX_LIST_LIMIT = 100;

  private final ProcessingJobRepository repository; private final Clock clock;
  public ProcessingJobService(ProcessingJobRepository repository, Clock clock){this.repository=repository; this.clock=clock;}
  @Transactional public ProcessingJob enqueue(UUID tenantId, String jobType, String targetType, UUID targetId){ return repository.findFirstByTenantIdAndTargetTypeAndTargetIdAndStatus(tenantId,targetType,targetId,"PENDING").orElseGet(() -> repository.save(new ProcessingJob(tenantId, jobType, targetType, targetId, 100, clock.instant()))); }

  // OP-CAP-28: bounded, tenant-scoped list. The limit is clamped to [1, MAX_LIST_LIMIT]; a null/<=0
  // limit falls back to DEFAULT_LIST_LIMIT. Always most-recent-first; never an unbounded scan.
  @Transactional(readOnly=true) public List<ProcessingJob> list(){ return list(DEFAULT_LIST_LIMIT); }
  @Transactional(readOnly=true) public List<ProcessingJob> list(Integer limit){
    int clamped = clampLimit(limit);
    return repository.findByTenantIdOrderByQueuedAtDesc(TenantContext.requireTenantId(), PageRequest.of(0, clamped));
  }

  // OP-CAP-28: tenant-scoped lookup. A missing OR cross-tenant id is reported identically as 404
  // NOT_FOUND — no cross-tenant existence is ever disclosed.
  @Transactional(readOnly=true) public ProcessingJob get(UUID id){ return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Processing job not found")); }

  // OP-CAP-28: controlled requeue. Tenant-scoped fetch first (cross-tenant => 404, never mutates), then
  // fail-closed eligibility: only a FAILED job under its attempt ceiling may be re-queued. Any other
  // state (PENDING/PROCESSING/SUCCEEDED/NEEDS_REVIEW/REJECTED, or exhausted attempts) => 409 CONFLICT
  // with no mutation. Requeue only flips the existing job back to PENDING (no second row), so it can
  // never duplicate active work; downstream execution still re-runs the runtime-control admission gate.
  @Transactional public ProcessingJob retry(UUID id){
    ProcessingJob job = get(id);
    if (!job.isRetryable()) {
      throw new ConflictException("Processing job is not retryable in its current state");
    }
    job.retry(clock.instant());
    return job;
  }

  private static int clampLimit(Integer limit){
    if (limit == null || limit <= 0) { return DEFAULT_LIST_LIMIT; }
    return Math.min(limit, MAX_LIST_LIMIT);
  }
}
