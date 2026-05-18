package com.orderpilot.application.services.extraction;

import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionRun;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionRunService {
  private final ExtractionRunRepository repository; private final AuditEventService auditEventService; private final Clock clock;
  public ExtractionRunService(ExtractionRunRepository repository, AuditEventService auditEventService, Clock clock){this.repository=repository; this.auditEventService=auditEventService; this.clock=clock;}
  @Transactional public ExtractionRun create(ExtractionRunRequest request, String providerName, String schemaVersion) {
    ExtractionRun run = new ExtractionRun(TenantContext.requireTenantId(), request.sourceType(), request.sourceId(), request.processingJobId(), request.providerType()==null?"RULE_BASED":request.providerType(), providerName, null, "stage4.prompt.v1", schemaVersion, clock.instant());
    ExtractionRun saved = repository.save(run);
    auditEventService.record("extraction_run.created", "extraction_run", saved.getId().toString(), null, "{\"source\":\"stage4\"}");
    return saved;
  }
  @Transactional(readOnly=true) public List<ExtractionRun> list(){ return repository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());}
  @Transactional(readOnly=true) public ExtractionRun get(UUID id){ return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Extraction run not found"));}
}