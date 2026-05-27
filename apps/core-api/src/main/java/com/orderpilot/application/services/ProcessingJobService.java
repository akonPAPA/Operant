package com.orderpilot.application.services;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessingJobService {
  private final ProcessingJobRepository repository; private final Clock clock;
  public ProcessingJobService(ProcessingJobRepository repository, Clock clock){this.repository=repository; this.clock=clock;}
  @Transactional public ProcessingJob enqueue(UUID tenantId, String jobType, String targetType, UUID targetId){ return repository.findFirstByTenantIdAndTargetTypeAndTargetIdAndStatus(tenantId,targetType,targetId,"PENDING").orElseGet(() -> repository.save(new ProcessingJob(tenantId, jobType, targetType, targetId, 100, clock.instant()))); }
  @Transactional(readOnly=true) public List<ProcessingJob> list(){ return repository.findByTenantIdOrderByQueuedAtDesc(TenantContext.requireTenantId()); }
  @Transactional(readOnly=true) public ProcessingJob get(UUID id){ return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Processing job not found")); }
  @Transactional public ProcessingJob retry(UUID id){ ProcessingJob job=get(id); job.retry(clock.instant()); return job; }
}
