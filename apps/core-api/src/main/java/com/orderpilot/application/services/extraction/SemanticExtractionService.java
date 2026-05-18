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
    var output = provider.extractStructuredData(text.getTextContent(), new SemanticExtractionProvider.ExtractionContext(run.getSourceType(), run.getProviderType()));
    sanitizer.validateProviderOutput(output);
    BigDecimal overall = confidence.overall(0.01 * Math.min(100, Math.max(0, text.getCharacterCount())), output.providerConfidence(), true, !warnings.isEmpty());
    String resultJson = json.writeObject(Map.of("provider", provider.providerName(), "warnings", output.warnings(), "promptInjectionWarnings", warnings, "advisoryOnly", true));
    ExtractionResult result = resultRepository.save(new ExtractionResult(run.getTenantId(), run.getId(), run.getSourceType(), run.getSourceId(), output.detectedIntent(), output.documentType(), overall, resultJson, warnings.isEmpty() ? "SCHEMA_VALID" : "SANITIZED", clock.instant()));
    SourceEvidence evidence = evidenceRepository.save(new SourceEvidence(run.getTenantId(), run.getId(), run.getSourceType(), run.getSourceId(), "MESSAGE_TEXT", 0, Math.min(text.getTextContent().length(), 240), sanitizer.sanitizeText(text.getTextContent().substring(0, Math.min(text.getTextContent().length(), 240))), clock.instant()));
    for (var field : output.fields()) {
      fieldRepository.save(new ExtractedField(run.getTenantId(), result.getId(), field.fieldName(), sanitizer.sanitizeText(field.rawValue()), sanitizer.sanitizeText(field.normalizedValue()), field.valueType(), confidence.field(field.confidence()), evidence.getId(), clock.instant()));
    }
    for (var line : output.lineItems()) {
      lineRepository.save(new ExtractedLineItem(run.getTenantId(), result.getId(), line.lineNumber(), sanitizer.sanitizeText(line.rawSku()), sanitizer.sanitizeText(line.rawDescription()), line.rawQuantity(), parseQuantity(line.rawQuantity()), line.rawUom(), line.rawUom(), confidence.field(line.confidence()), evidence.getId(), clock.instant()));
    }
    for (String warning : warnings) {
      suggestionRepository.save(new AiSuggestion(run.getTenantId(), run.getId(), "WARNING", json.writeObject(Map.of("warning", warning, "advisoryOnly", true)), BigDecimal.valueOf(0.9), "CREATED", clock.instant()));
    }
    suggestionRepository.save(new AiSuggestion(run.getTenantId(), run.getId(), "SUMMARY", json.writeObject(Map.of("intent", output.detectedIntent(), "advisoryOnly", true)), overall, "CREATED", clock.instant()));
    return result;
  }
  private BigDecimal parseQuantity(String raw){ try { return raw == null ? null : new BigDecimal(raw); } catch (NumberFormatException ex) { return null; } }
}