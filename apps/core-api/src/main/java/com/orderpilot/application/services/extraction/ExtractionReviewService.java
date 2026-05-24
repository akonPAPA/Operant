package com.orderpilot.application.services.extraction;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionReviewService {
  private final ExtractionResultRepository resultRepository; private final ExtractedFieldRepository fieldRepository; private final ExtractedLineItemRepository lineRepository; private final SourceEvidenceRepository evidenceRepository; private final AiSuggestionRepository suggestionRepository; private final Clock clock;
  public ExtractionReviewService(ExtractionResultRepository resultRepository, ExtractedFieldRepository fieldRepository, ExtractedLineItemRepository lineRepository, SourceEvidenceRepository evidenceRepository, AiSuggestionRepository suggestionRepository, Clock clock){this.resultRepository=resultRepository; this.fieldRepository=fieldRepository; this.lineRepository=lineRepository; this.evidenceRepository=evidenceRepository; this.suggestionRepository=suggestionRepository; this.clock=clock;}
  @Transactional(readOnly=true) public List<ExtractionResult> results(){return resultRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());}
  @Transactional(readOnly=true) public ExtractionResult result(UUID id){return resultRepository.findByIdAndTenantId(id,TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Extraction result not found"));}
  @Transactional(readOnly=true) public ExtractionResult resultForRun(UUID runId){return resultRepository.findFirstByTenantIdAndExtractionRunId(TenantContext.requireTenantId(),runId).orElseThrow(() -> new IllegalArgumentException("Extraction result not found"));}
  @Transactional(readOnly=true) public List<ExtractionResult> resultsForSource(String sourceType, UUID sourceId){return resultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(TenantContext.requireTenantId(), sourceType, sourceId);}
  @Transactional(readOnly=true) public List<ExtractedField> fields(UUID resultId){return fieldRepository.findByTenantIdAndExtractionResultId(TenantContext.requireTenantId(), resultId);}
  @Transactional(readOnly=true) public List<ExtractedLineItem> lineItems(UUID resultId){return lineRepository.findByTenantIdAndExtractionResultId(TenantContext.requireTenantId(), resultId);}
  @Transactional(readOnly=true) public List<SourceEvidence> evidence(UUID runId){return evidenceRepository.findByTenantIdAndExtractionRunId(TenantContext.requireTenantId(), runId);}
  @Transactional(readOnly=true) public List<AiSuggestion> suggestions(UUID runId){return suggestionRepository.findByTenantIdAndExtractionRunId(TenantContext.requireTenantId(), runId);}
  @Transactional public ExtractedField markField(UUID id, String status){ExtractedField f=fieldRepository.findByIdAndTenantId(id,TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Extracted field not found")); f.setValidationStatus(status, clock.instant()); return f;}
  @Transactional public ExtractedLineItem markLine(UUID id, String status){ExtractedLineItem l=lineRepository.findByIdAndTenantId(id,TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Extracted line item not found")); l.setValidationStatus(status, clock.instant()); return l;}
}
