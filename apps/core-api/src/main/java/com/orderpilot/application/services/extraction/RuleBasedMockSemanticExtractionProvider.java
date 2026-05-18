package com.orderpilot.application.services.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedMockSemanticExtractionProvider implements SemanticExtractionProvider {
  private static final Pattern QTY = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*(EA|PCS|PC|BOX|SET|UNIT|UNITS)?\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern SKU = Pattern.compile("\\b[A-Z0-9][A-Z0-9._-]{2,}\\b");
  public SemanticExtractionOutput extractStructuredData(String text, ExtractionContext context) {
    String safe = text == null ? "" : text;
    String lower = safe.toLowerCase();
    String intent = lower.contains("purchase order") || lower.contains(" po ") ? "PURCHASE_ORDER" : lower.contains("price") ? "PRICE_INQUIRY" : lower.contains("stock") || lower.contains("available") ? "AVAILABILITY_CHECK" : lower.contains("need") || lower.contains("rfq") ? "RFQ" : "UNKNOWN";
    String docType = "CHANNEL_MESSAGE".equals(context.sourceType()) ? "MESSAGE" : ("PURCHASE_ORDER".equals(intent) ? "PURCHASE_ORDER" : "RFQ".equals(intent) ? "RFQ" : "UNKNOWN");
    List<FieldCandidate> fields = new ArrayList<>();
    var qty = QTY.matcher(safe);
    if (qty.find()) fields.add(new FieldCandidate("quantity", qty.group(1), qty.group(1), "QUANTITY", 0.78, qty.start(1), qty.end(1)));
    var sku = SKU.matcher(safe.toUpperCase());
    if (sku.find()) fields.add(new FieldCandidate("sku", sku.group(), sku.group(), "SKU", 0.66, sku.start(), sku.end()));
    List<LineItemCandidate> lines = new ArrayList<>();
    if (!fields.isEmpty()) {
      String rawQty = fields.stream().filter(f -> f.fieldName().equals("quantity")).map(FieldCandidate::rawValue).findFirst().orElse(null);
      String rawSku = fields.stream().filter(f -> f.fieldName().equals("sku")).map(FieldCandidate::rawValue).findFirst().orElse(null);
      lines.add(new LineItemCandidate(1, rawSku, safe.length() > 180 ? safe.substring(0, 180) : safe, rawQty, "EA", 0.62, 0, Math.min(safe.length(), 180)));
    }
    return new SemanticExtractionOutput(intent, docType, fields.isEmpty() ? 0.35 : 0.70, fields, lines, List.of("rule_based_mock_output_advisory_only"));
  }
  public String providerName(){return "rule-based-mock";}
  public String schemaVersion(){return "stage4.v1";}
}