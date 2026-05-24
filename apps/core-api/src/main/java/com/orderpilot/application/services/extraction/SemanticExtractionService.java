package com.orderpilot.application.services.extraction;

import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.domain.extraction.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SemanticExtractionService {
  private final SemanticExtractionProvider provider; private final ExtractionOutputSanitizer sanitizer; private final ConfidenceScoringService confidence; private final JsonSupport json; private final ExtractionResultRepository resultRepository; private final SourceEvidenceRepository evidenceRepository; private final ExtractedFieldRepository fieldRepository; private final ExtractedLineItemRepository lineRepository; private final AiSuggestionRepository suggestionRepository; private final PromptInjectionGuardService promptGuard; private final Clock clock;
  public SemanticExtractionService(SemanticExtractionProvider provider, ExtractionOutputSanitizer sanitizer, ConfidenceScoringService confidence, JsonSupport json, ExtractionResultRepository resultRepository, SourceEvidenceRepository evidenceRepository, ExtractedFieldRepository fieldRepository, ExtractedLineItemRepository lineRepository, AiSuggestionRepository suggestionRepository, PromptInjectionGuardService promptGuard, Clock clock){this.provider=provider; this.sanitizer=sanitizer; this.confidence=confidence; this.json=json; this.resultRepository=resultRepository; this.evidenceRepository=evidenceRepository; this.fieldRepository=fieldRepository; this.lineRepository=lineRepository; this.suggestionRepository=suggestionRepository; this.promptGuard=promptGuard; this.clock=clock;}
  public ExtractionResult extractAndStore(ExtractionRun run, ExtractedDocumentText text) {
    var warnings = promptGuard.detect(text.getTextContent());
    var output = provider.extractStructuredData(text.getTextContent(), new SemanticExtractionProvider.ExtractionContext(run.getSourceType(), run.getProviderType(), run.getSourceType()));
    sanitizer.validateProviderOutput(output);
    BigDecimal overall = confidence.overall(0.01 * Math.min(100, Math.max(0, text.getCharacterCount())), output.providerConfidence(), true, !warnings.isEmpty());
    String status = warnings.isEmpty() && overall.compareTo(BigDecimal.valueOf(0.50)) >= 0 ? "READY_FOR_VALIDATION" : "NEEDS_REVIEW";
    String resultJson = json.writeObject(Map.of(
        "provider", provider.providerName(),
        "model", "mock-rule-based",
        "promptVersion", "stage4.prompt.v1",
        "schemaVersion", provider.schemaVersion(),
        "extractionMethod", text.getExtractionMethod(),
        "sourceChannelContext", output.sourceChannelContext(),
        "customerHints", output.customerHints(),
        "warnings", output.warnings(),
        "promptInjectionWarnings", warnings,
        "advisoryOnly", true));
    ExtractionResult result = resultRepository.save(new ExtractionResult(run.getTenantId(), run.getId(), run.getSourceType(), run.getSourceId(), output.detectedIntent(), output.documentType(), overall, resultJson, status, clock.instant()));
    for (var field : output.fields()) {
      SourceEvidence evidence = evidenceRepository.save(evidenceFor(run, text.getTextContent(), field.startOffset(), field.endOffset()));
      fieldRepository.save(new ExtractedField(run.getTenantId(), result.getId(), field.fieldName(), sanitizer.sanitizeText(field.rawValue()), sanitizer.sanitizeText(field.normalizedValue()), field.valueType(), confidence.field(field.confidence()), evidence.getId(), clock.instant()));
    }
    for (var line : output.lineItems()) {
      SourceEvidence evidence = evidenceRepository.save(evidenceFor(run, text.getTextContent(), line.startOffset(), line.endOffset()));
      lineRepository.save(new ExtractedLineItem(run.getTenantId(), result.getId(), line.lineNumber(), sanitizer.sanitizeText(line.rawSku()), sanitizer.sanitizeText(line.rawDescription()), line.rawQuantity(), parseQuantity(line.rawQuantity()), line.rawUom(), line.rawUom(), confidence.field(line.confidence()), evidence.getId(), clock.instant()));
    }
    for (String warning : warnings) {
      suggestionRepository.save(new AiSuggestion(run.getTenantId(), run.getId(), "WARNING", json.writeObject(Map.of("warning", warning, "advisoryOnly", true)), BigDecimal.valueOf(0.9), "CREATED", clock.instant()));
    }
    suggestionRepository.save(new AiSuggestion(run.getTenantId(), run.getId(), "SUMMARY", json.writeObject(Map.of("intent", output.detectedIntent(), "advisoryOnly", true)), overall, "CREATED", clock.instant()));
    return result;
  }

  private SourceEvidence evidenceFor(ExtractionRun run, String text, int startOffset, int endOffset) {
    int safeStart = Math.max(0, Math.min(startOffset, text.length()));
    int safeEnd = Math.max(safeStart, Math.min(endOffset, text.length()));
    String snippet = text.substring(safeStart, safeEnd);
    if (snippet.isBlank()) {
      safeStart = 0;
      safeEnd = Math.min(text.length(), 240);
      snippet = text.substring(safeStart, safeEnd);
    }
    String evidenceType = "CHANNEL_MESSAGE".equals(run.getSourceType()) ? "MESSAGE_TEXT_SPAN" : "DOCUMENT_TEXT_SPAN";
    return new SourceEvidence(run.getTenantId(), run.getId(), run.getSourceType(), run.getSourceId(), evidenceType, safeStart, safeEnd, sanitizer.sanitizeText(snippet), clock.instant());
  }

  private BigDecimal parseQuantity(String raw){ try { return raw == null ? null : new BigDecimal(raw); } catch (NumberFormatException ex) { return null; } }
}
