package com.orderpilot.application.services.extraction;

import com.orderpilot.domain.intake.InboundDocumentRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MockDocumentTextExtractionProvider implements TextExtractionProvider {
  private final InboundDocumentRepository repository;
  public MockDocumentTextExtractionProvider(InboundDocumentRepository repository){this.repository=repository;}
  public boolean supports(String sourceType){return "INBOUND_DOCUMENT".equals(sourceType) || "INBOUND_ATTACHMENT".equals(sourceType);}
  public TextExtractionOutput extractText(UUID tenantId, String sourceType, UUID sourceId){
    if ("INBOUND_DOCUMENT".equals(sourceType)) {
      var doc = repository.findByIdAndTenantId(sourceId, tenantId).orElseThrow(() -> new IllegalArgumentException("Inbound document not found"));
      String text = "Mock extracted text from " + (doc.getOriginalFilename()==null ? "inbound document" : doc.getOriginalFilename()) + ". Need 10 EA SKU-001.";
      return new TextExtractionOutput(text, "PDF_TEXT_PLACEHOLDER", null, 1, 0.50);
    }
    return new TextExtractionOutput("Mock extracted attachment text. Need 10 EA SKU-001.", "MOCK", null, 1, 0.40);
  }
  public String providerName(){return "mock-document-text";}
}