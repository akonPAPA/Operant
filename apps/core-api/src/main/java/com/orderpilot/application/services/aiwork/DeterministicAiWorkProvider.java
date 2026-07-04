package com.orderpilot.application.services.aiwork;

import com.orderpilot.domain.aiwork.AiWorkSchemaVersion;
import com.orderpilot.domain.aiwork.AiWorkType;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * OP-CAP-07A default {@link AiWorkProvider}: a deterministic, provider-agnostic generator.
 *
 * <p>It produces stable advisory content from the supplied context without any external LLM call or
 * secret, which keeps the layer testable and demo-safe. The output is intentionally conservative and
 * always frames suggestions as advisory, draft-only, and subject to human approval. A real
 * LLM-backed provider can replace this later behind the same {@link AiWorkProvider} contract.
 */
@Component
public class DeterministicAiWorkProvider implements AiWorkProvider {
  static final String STRATEGY_VERSION = "deterministic-v1";

  @Override
  public String strategyVersion() {
    return STRATEGY_VERSION;
  }

  @Override
  public AiWorkGenerationResult generate(AiWorkGenerationRequest request) {
    String context = request.contextText() == null ? "" : request.contextText().strip();
    String contextSnippet = snippet(context);
    AiWorkType type = request.workType();
    return switch (type) {
      case REQUEST_SUMMARY -> result(
          "Summary (advisory): " + (context.isBlank()
              ? "No inbound context was provided for this source."
              : "Customer request context indicates: " + contextSnippet),
          schemaPrefix(AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_REQUEST_SUMMARY)
              + "\"highlights\":[" + jsonStr(contextSnippet) + "]}",
          "LOW", new BigDecimal("0.60"));
      case VALIDATION_EXPLANATION -> result(
          "Validation explanation (advisory): review the flagged validation issues against the "
              + "source context before acting. " + (context.isBlank() ? "" : "Context: " + contextSnippet),
          schemaPrefix(AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION)
              + "\"explanationBasis\":\"VALIDATION_AND_SOURCE_CONTEXT\"}",
          "LOW", new BigDecimal("0.55"));
      case CUSTOMER_REPLY_DRAFT -> result(
          "Draft only — not sent. Suggested customer reply: \"Thank you for your message. We are "
              + "reviewing your request and will follow up shortly with confirmed details.\"",
          schemaPrefix(AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT)
              + "\"channelSafe\":true,\"containsCommitments\":false}",
          // Customer-facing text is higher risk: it must be operator-reviewed before any send path.
          "MEDIUM", new BigDecimal("0.50"));
      case NEXT_ACTION_SUGGESTION -> result(
          "Next-action candidates (advisory): each requires human approval before execution.",
          nextActionPayload(context),
          riskForNextActions(context), new BigDecimal("0.50"));
      case SOURCE_CONTEXT_DIGEST -> result(
          "Source context digest (advisory): " + (context.isBlank()
              ? "No structured source context available."
              : contextSnippet),
          schemaPrefix(AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_REQUEST_SUMMARY)
              + "\"digest\":" + jsonStr(contextSnippet) + "}",
          "LOW", new BigDecimal("0.60"));
    };
  }

  private AiWorkGenerationResult result(String text, String payloadJson, String risk, BigDecimal confidence) {
    // Evidence always anchors back to the source object this suggestion was generated for.
    String evidence =
        "[{\"sourceType\":\"SOURCE_OBJECT\",\"sourceLabel\":\"Source object\","
            + "\"excerpt\":\"Anchored to backend-resolved source context.\"}]";
    return new AiWorkGenerationResult(text, payloadJson, evidence, risk, confidence, STRATEGY_VERSION);
  }

  /** Candidate internal next actions. All are advisory and flagged as requiring human approval. */
  private String nextActionPayload(String context) {
    String lower = context.toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(
        schemaPrefix(AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION))
        .append("\"candidates\":[");
    sb.append(candidate("ASK_CUSTOMER_FOR_INFO", "Ask customer for missing information"));
    sb.append(",").append(candidate("ROUTE_TO_REVIEW", "Route to operator review"));
    if (lower.contains("alias") || lower.contains("part") || lower.contains("sku")) {
      sb.append(",").append(candidate("CHECK_PRODUCT_ALIAS", "Check product alias mapping"));
    }
    if (lower.contains("substitute") || lower.contains("alternative")) {
      sb.append(",").append(candidate("REVIEW_SUBSTITUTE", "Review possible substitute"));
    }
    if (lower.contains("discount") || lower.contains("margin") || lower.contains("price")) {
      sb.append(",").append(candidate("REVIEW_MARGIN_DISCOUNT", "Review margin/discount"));
    }
    sb.append(",").append(candidate("CREATE_QUOTE_DRAFT", "Create quote draft through existing workflow"));
    sb.append(",").append(candidate("ESCALATE_TO_HUMAN", "Escalate to a human specialist"));
    sb.append("]}");
    return sb.toString();
  }

  private String candidate(String actionCode, String label) {
    // requiresHumanApproval is always true: this layer never auto-executes a business action.
    return "{\"actionType\":" + jsonStr(actionCode) + ",\"label\":" + jsonStr(label)
        + ",\"requiresHumanApproval\":true}";
  }

  private static String schemaPrefix(AiWorkSchemaVersion schema) {
    return "{\"schemaVersion\":" + jsonStr(schema.name()) + ",";
  }

  private String riskForNextActions(String context) {
    String lower = context.toLowerCase(Locale.ROOT);
    return (lower.contains("discount") || lower.contains("margin") || lower.contains("substitute"))
        ? "HIGH" : "MEDIUM";
  }

  private static String snippet(String context) {
    if (context == null || context.isBlank()) return "";
    String trimmed = context.strip();
    return trimmed.length() <= 280 ? trimmed : trimmed.substring(0, 280) + "…";
  }

  /** Minimal JSON string escaper for deterministic, injection-safe payloads. */
  private static String jsonStr(String value) {
    if (value == null) return "\"\"";
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append("\"").toString();
  }
}
