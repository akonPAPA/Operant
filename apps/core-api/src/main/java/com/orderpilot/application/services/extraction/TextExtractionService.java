package com.orderpilot.application.services.extraction;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedDocumentText;
import com.orderpilot.domain.extraction.ExtractedDocumentTextRepository;
import com.orderpilot.domain.extraction.ExtractionRun;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TextExtractionService {
  private final List<TextExtractionProvider> providers;
  private final ExtractedDocumentTextRepository repository;
  private final Clock clock;

  public TextExtractionService(List<TextExtractionProvider> providers, ExtractedDocumentTextRepository repository, Clock clock) {
    this.providers = providers;
    this.repository = repository;
    this.clock = clock;
  }

  public ExtractedDocumentText extractAndStore(ExtractionRun run) {
    TextExtractionProvider provider = providers.stream()
        .filter(p -> p.supports(run.getSourceType()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No text extraction provider for source type"));
    TextExtractionProvider.TextExtractionOutput output = provider.extractText(run.getTenantId(), run.getSourceType(), run.getSourceId());
    return repository.save(new ExtractedDocumentText(
        run.getTenantId(),
        run.getId(),
        run.getSourceType(),
        run.getSourceId(),
        output.text(),
        output.language(),
        output.extractionMethod(),
        output.pageCount(),
        BigDecimal.valueOf(output.qualityScore()),
        clock.instant()
    ));
  }

  public ExtractedDocumentText getForRun(UUID runId) {
    return repository.findFirstByTenantIdAndExtractionRunId(TenantContext.requireTenantId(), runId)
        .orElseThrow(() -> new IllegalArgumentException("Extracted text not found"));
  }
}