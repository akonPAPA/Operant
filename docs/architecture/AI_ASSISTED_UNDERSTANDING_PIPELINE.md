# AI-Assisted Understanding Pipeline

```text
InboundDocument / ChannelMessage
  -> TextExtractionProvider
  -> ExtractedDocumentText
  -> PromptInjectionGuard
  -> SemanticExtractionProvider
  -> ExtractionOutputSanitizer
  -> ExtractionResult
  -> ExtractedField / ExtractedLineItem / SourceEvidence
  -> AiSuggestion
  -> Stage 5 deterministic validation
```

Stage 4 uses mock and rule-based providers only. Provider output is advisory and untrusted.

No extraction output updates product, customer, inventory, price, quote, order, ERP, 1C, accounting, or warehouse data.