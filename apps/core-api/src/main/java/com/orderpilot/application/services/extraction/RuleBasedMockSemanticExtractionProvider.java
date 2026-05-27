package com.orderpilot.application.services.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.orderpilot.domain.extraction.DocumentIntent;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedMockSemanticExtractionProvider implements SemanticExtractionProvider {
  private static final Pattern QTY = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*(EA|PCS|PC|BOX|SET|UNIT|UNITS)?\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern SKU = Pattern.compile("\\b(?=[A-Z0-9._-]*\\d)[A-Z0-9][A-Z0-9._-]{2,}\\b");
  private static final Pattern DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{2,4})\\b");
  private static final Pattern LOCATION = Pattern.compile("\\b(?:ship(?:\\s+to)?|deliver(?:\\s+to)?|location)[:\\s]+([A-Za-z0-9 ._-]{2,40})", Pattern.CASE_INSENSITIVE);
  private static final Pattern CUSTOMER = Pattern.compile("\\b(?:customer|account|company)[:\\s]+([A-Za-z0-9 ._-]{2,60})", Pattern.CASE_INSENSITIVE);

  public SemanticExtractionOutput extractStructuredData(String text, ExtractionContext context) {
    String safe = text == null ? "" : text;
    String lower = safe.toLowerCase();
    DocumentIntent intent = detectIntent(lower);
    String docType = "CHANNEL_MESSAGE".equals(context.sourceType()) ? "message" : (intent == DocumentIntent.PURCHASE_ORDER ? "purchase_order" : intent == DocumentIntent.RFQ ? "RFQ" : "unknown");
    List<FieldCandidate> fields = new ArrayList<>();
    var qty = QTY.matcher(safe);
    String rawQty = null;
    String rawUom = null;
    if (qty.find()) {
      rawQty = qty.group(1);
      rawUom = qty.group(2) == null ? null : qty.group(2).toUpperCase();
      fields.add(new FieldCandidate("quantity", rawQty, rawQty, "quantity", 0.78, qty.start(1), qty.end(1)));
      if (rawUom != null) fields.add(new FieldCandidate("uom", qty.group(2), rawUom, "uom", 0.74, qty.start(2), qty.end(2)));
    }
    var sku = SKU.matcher(safe.toUpperCase());
    String rawSku = null;
    if (sku.find()) {
      rawSku = sku.group();
      fields.add(new FieldCandidate("product_sku_hint", rawSku, rawSku, "sku", 0.66, sku.start(), sku.end()));
    }
    var date = DATE.matcher(safe);
    String requestedDate = null;
    if (date.find()) {
      requestedDate = date.group(1);
      fields.add(new FieldCandidate("requested_date", requestedDate, requestedDate, "date", 0.58, date.start(1), date.end(1)));
    }
    var location = LOCATION.matcher(safe);
    String locationHint = null;
    if (location.find()) {
      locationHint = location.group(1).trim();
      fields.add(new FieldCandidate("delivery_location_hint", locationHint, locationHint, "location_hint", 0.56, location.start(1), location.end(1)));
    }
    List<String> customerHints = new ArrayList<>();
    var customer = CUSTOMER.matcher(safe);
    if (customer.find()) {
      customerHints.add(customer.group(1).trim());
      fields.add(new FieldCandidate("customer_hint", customer.group(1).trim(), customer.group(1).trim(), "customer_hint", 0.52, customer.start(1), customer.end(1)));
    }
    List<LineItemCandidate> lines = new ArrayList<>();
    if (rawQty != null || rawSku != null) {
      String description = safe.length() > 180 ? safe.substring(0, 180) : safe;
      fields.add(new FieldCandidate("product_description", description, description, "description", 0.50, 0, Math.min(safe.length(), 180)));
      lines.add(new LineItemCandidate(1, rawSku, rawSku, description, rawQty, rawUom == null ? "EA" : rawUom, requestedDate, locationHint, 0.62, 0, Math.min(safe.length(), 180)));
    }
    if (!lines.isEmpty()) {
      fields.add(new FieldCandidate("raw_line_items", safe.substring(0, Math.min(safe.length(), 180)), safe.substring(0, Math.min(safe.length(), 180)), "line_items", 0.62, 0, Math.min(safe.length(), 180)));
    }
    return new SemanticExtractionOutput(intent.name(), docType, fields.isEmpty() ? 0.35 : 0.70, customerHints, context.sourceChannelContext(), fields, lines, List.of("rule_based_mock_output_advisory_only"));
  }

  private DocumentIntent detectIntent(String lower) {
    if (lower.contains("purchase order") || lower.contains(" po ")) return DocumentIntent.PURCHASE_ORDER;
    if (lower.contains("order status") || lower.contains("where is my order") || lower.contains("tracking")) return DocumentIntent.ORDER_STATUS_REQUEST;
    if (lower.contains("availability") || lower.contains("in stock")) return DocumentIntent.AVAILABILITY_REQUEST;
    if (lower.contains("price")) return DocumentIntent.PRICE_REQUEST;
    if (lower.contains("need") || lower.contains("rfq") || lower.contains("quote")) return DocumentIntent.RFQ;
    return DocumentIntent.UNKNOWN;
  }

  public String providerName(){return "rule-based-mock";}
  public String schemaVersion(){return "stage4.v1";}
}
