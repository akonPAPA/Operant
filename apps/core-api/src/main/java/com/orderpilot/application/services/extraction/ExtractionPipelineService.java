package com.orderpilot.application.services.extraction;

import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.domain.extraction.*;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionPipelineService {
  private final ExtractionRunService runService; private final TextExtractionService textService; private final SemanticExtractionService semanticService; private final AuditEventService auditEventService; private final SemanticExtractionProvider provider; private final Clock clock;
  public ExtractionPipelineService(ExtractionRunService runService, TextExtractionService textService, SemanticExtractionService semanticService, AuditEventService auditEventService, SemanticExtractionProvider provider, Clock clock){this.runService=runService; this.textService=textService; this.semanticService=semanticService; this.auditEventService=auditEventService; this.provider=provider; this.clock=clock;}
  @Transactional public ExtractionRun runNow(ExtractionRunRequest request) {
    ExtractionRun run = runService.create(request, provider.providerName(), provider.schemaVersion());
    try {
      run.markRunning(clock.instant());
      ExtractedDocumentText text = textService.extractAndStore(run);
      semanticService.extractAndStore(run, text);
      run.markSucceeded(clock.instant());
      auditEventService.record("extraction_run.succeeded", "extraction_run", run.getId().toString(), null, "{\"stage\":\"4\"}");
      return run;
    } catch (RuntimeException ex) {
      run.markFailed(ex.getMessage(), clock.instant());
      auditEventService.record("extraction_run.failed", "extraction_run", run.getId().toString(), null, "{\"stage\":\"4\"}");
      throw ex;
    }
  }
}