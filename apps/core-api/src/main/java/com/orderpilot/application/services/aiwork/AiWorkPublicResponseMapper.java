package com.orderpilot.application.services.aiwork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkDisplayField;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkEvidenceItem;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkNextActionCandidate;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkRiskFlag;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkSafety;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkSuggestionResponse;
import com.orderpilot.domain.aiwork.AiWorkSchemaVersion;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import com.orderpilot.domain.aiwork.AiWorkType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates untrusted provider output against a known schema and maps it to an allowlisted public
 * projection. Raw provider text and JSON are never used as a fallback.
 */
@Component
public class AiWorkPublicResponseMapper {
  static final String SAFE_FAILURE_MESSAGE = "AI suggestion could not be safely rendered.";

  private static final int MAX_DISPLAY_FIELDS = 24;
  private static final int MAX_EVIDENCE_ITEMS = 12;
  private static final int MAX_FIELD_CHARS = 256;
  private static final int MAX_SUMMARY_CHARS = 1_000;
  private static final int MAX_NEXT_ACTIONS = 16;
  private static final Pattern SENSITIVE_MARKER = Pattern.compile(
      "(?i)(api[_-]?key|token|password|credential|secret|authorization|bearer\\s+|"
          + "prompt|raw[_-]?payload|payload[_-]?json|tenant[_-]?id|actor[_-]?id|"
          + "idempotency[_-]?key|audit[_-]?id|stack[_-]?trace|object[_-]?storage[_-]?key)");

  private final ObjectMapper objectMapper;

  public AiWorkPublicResponseMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public AiWorkSuggestionResponse toResponse(AiWorkSuggestion suggestion) {
    AiWorkType workType = AiWorkType.valueOf(suggestion.getWorkType());
    AiWorkSchemaVersion schema = AiWorkSchemaVersion.forWorkType(workType);
    ParsedPayload parsed = parsePayload(suggestion.getStructuredPayloadJson(), schema, workType);
    String publicRiskLevel = normalizedRisk(suggestion.getRiskLevel());
    String summary = parsed.valid()
        ? safeText(suggestion.getGeneratedText(), MAX_SUMMARY_CHARS)
        : SAFE_FAILURE_MESSAGE;
    if (summary == null) {
      summary = SAFE_FAILURE_MESSAGE;
    }

    List<AiWorkEvidenceItem> evidence =
        parsed.valid() ? parseEvidence(suggestion.getEvidenceRefsJson()) : List.of();
    List<AiWorkRiskFlag> riskFlags = riskFlags(publicRiskLevel, parsed);
    boolean humanApprovalRequired = requiresHumanApproval(publicRiskLevel, parsed);
    AiWorkSafety safety = new AiWorkSafety(
        true, "DISABLED", "NOT_INVOKED", "NOT_REQUESTED", humanApprovalRequired);

    return new AiWorkSuggestionResponse(
        suggestion.getId(),
        suggestion.getWorkType(),
        suggestion.getSourceType(),
        suggestion.getStatus(),
        schema.name(),
        publicRiskLevel,
        boundedConfidence(suggestion.getConfidence()),
        summary,
        parsed.valid() ? parsed.displayFields() : List.of(),
        evidence,
        parsed.valid() ? parsed.nextActions() : List.of(),
        riskFlags,
        safety,
        true,
        suggestion.getCreatedAt(),
        suggestion.getUpdatedAt(),
        suggestion.getDecidedAt(),
        safeText(suggestion.getDecisionReason(), MAX_FIELD_CHARS));
  }

  private ParsedPayload parsePayload(
      String rawJson, AiWorkSchemaVersion expectedSchema, AiWorkType workType) {
    if (rawJson == null || rawJson.isBlank()) {
      return ParsedPayload.invalid();
    }
    try {
      JsonNode root = objectMapper.readTree(rawJson);
      if (!root.isObject()
          || !expectedSchema.name().equals(textOrNull(root.path("schemaVersion")))) {
        return ParsedPayload.invalid();
      }
      return switch (expectedSchema) {
        case AI_WORK_SCHEMA_V1_REQUEST_SUMMARY -> parseRequestSummary(root, workType);
        case AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION -> parseNextActions(root);
        case AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT -> parseCustomerReply(root);
        case AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION -> parseValidationExplanation(root);
      };
    } catch (Exception ignored) {
      return ParsedPayload.invalid();
    }
  }

  private ParsedPayload parseRequestSummary(JsonNode root, AiWorkType workType) {
    List<AiWorkDisplayField> fields = new ArrayList<>();
    if (workType == AiWorkType.SOURCE_CONTEXT_DIGEST) {
      String digest = safeText(textOrNull(root.get("digest")), MAX_FIELD_CHARS);
      if (digest == null) {
        return ParsedPayload.invalid();
      }
      fields.add(field("digest", "Digest", digest, "TEXT"));
      return ParsedPayload.valid(fields, List.of());
    }
    JsonNode highlights = root.get("highlights");
    if (highlights == null || !highlights.isArray()) {
      return ParsedPayload.invalid();
    }
    int index = 0;
    for (JsonNode node : highlights) {
      if (fields.size() >= MAX_DISPLAY_FIELDS) {
        break;
      }
      String value = safeText(textOrNull(node), MAX_FIELD_CHARS);
      if (value != null) {
        fields.add(field("highlight_" + index, "Highlight", value, "TEXT"));
      }
      index++;
    }
    return ParsedPayload.valid(fields, List.of());
  }

  private ParsedPayload parseValidationExplanation(JsonNode root) {
    String basis = safeText(textOrNull(root.get("explanationBasis")), MAX_FIELD_CHARS);
    if (basis == null) {
      return ParsedPayload.invalid();
    }
    return ParsedPayload.valid(
        List.of(field("explanation_basis", "Explanation basis", basis, "TEXT")), List.of());
  }

  private ParsedPayload parseCustomerReply(JsonNode root) {
    JsonNode channelSafe = root.get("channelSafe");
    JsonNode containsCommitments = root.get("containsCommitments");
    if (channelSafe == null
        || !channelSafe.isBoolean()
        || containsCommitments == null
        || !containsCommitments.isBoolean()) {
      return ParsedPayload.invalid();
    }
    return ParsedPayload.valid(
        List.of(
            field("channel_safe", "Channel safe", yesNo(channelSafe.asBoolean()), "BOOLEAN"),
            field(
                "contains_commitments",
                "Contains commitments",
                yesNo(containsCommitments.asBoolean()),
                "BOOLEAN")),
        List.of());
  }

  private ParsedPayload parseNextActions(JsonNode root) {
    JsonNode candidates = root.get("candidates");
    if (candidates == null || !candidates.isArray()) {
      return ParsedPayload.invalid();
    }
    List<AiWorkNextActionCandidate> actions = new ArrayList<>();
    for (JsonNode candidate : candidates) {
      if (actions.size() >= MAX_NEXT_ACTIONS) {
        break;
      }
      String actionType = safeIdentifier(textOrNull(candidate.path("actionType")));
      String label = safeText(textOrNull(candidate.path("label")), MAX_FIELD_CHARS);
      if (actionType == null || label == null) {
        continue;
      }
      String description =
          safeText(textOrNull(candidate.path("description")), MAX_FIELD_CHARS);
      String disabledReason =
          safeText(textOrNull(candidate.path("disabledReason")), MAX_FIELD_CHARS);
      // Provider output cannot waive backend/human approval.
      actions.add(
          new AiWorkNextActionCandidate(
              actionType, label, description, true, disabledReason));
    }
    if (actions.isEmpty()) {
      return ParsedPayload.invalid();
    }
    return ParsedPayload.valid(List.of(), actions);
  }

  private List<AiWorkEvidenceItem> parseEvidence(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = objectMapper.readTree(rawJson);
      if (!root.isArray()) {
        return List.of();
      }
      List<AiWorkEvidenceItem> items = new ArrayList<>();
      for (JsonNode node : root) {
        if (items.size() >= MAX_EVIDENCE_ITEMS) {
          break;
        }
        if (!node.isObject()) {
          continue;
        }
        String sourceType = allowlistedEvidenceType(textOrNull(node.path("sourceType")));
        String sourceLabel = evidenceLabel(sourceType);
        String excerpt = safeText(textOrNull(node.path("excerpt")), MAX_FIELD_CHARS);
        BigDecimal confidence = decimalOrNull(node.path("confidence"));
        items.add(new AiWorkEvidenceItem(sourceType, sourceLabel, excerpt, confidence));
      }
      return List.copyOf(items);
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static List<AiWorkRiskFlag> riskFlags(String riskLevel, ParsedPayload parsed) {
    List<AiWorkRiskFlag> flags = new ArrayList<>();
    String severity = normalizedRisk(riskLevel);
    flags.add(new AiWorkRiskFlag(
        "AI_WORK_RISK_" + severity,
        severity,
        "Backend risk classification: " + severity + "."));
    if (requiresHumanApproval(riskLevel, parsed)) {
      flags.add(new AiWorkRiskFlag(
          "REQUIRES_HUMAN_APPROVAL",
          "MEDIUM",
          "A human must approve any resulting business action."));
    }
    return List.copyOf(flags);
  }

  private static boolean requiresHumanApproval(String riskLevel, ParsedPayload parsed) {
    if (!"LOW".equals(normalizedRisk(riskLevel))) {
      return true;
    }
    return parsed != null && !parsed.nextActions().isEmpty();
  }

  private static String normalizedRisk(String value) {
    if (value == null) {
      return "HIGH";
    }
    return switch (value.strip().toUpperCase(Locale.ROOT)) {
      case "LOW" -> "LOW";
      case "MEDIUM" -> "MEDIUM";
      default -> "HIGH";
    };
  }

  private static AiWorkDisplayField field(
      String key, String label, String value, String kind) {
    return new AiWorkDisplayField(key, label, value, kind, null, null);
  }

  private static String yesNo(boolean value) {
    return value ? "yes" : "no";
  }

  private static String allowlistedEvidenceType(String value) {
    if (value == null) {
      return "SOURCE_CONTEXT";
    }
    return switch (value.strip().toUpperCase(Locale.ROOT)) {
      case "SOURCE_OBJECT" -> "SOURCE_OBJECT";
      case "VALIDATION_RESULT" -> "VALIDATION_RESULT";
      case "OPERATOR_CONTEXT" -> "OPERATOR_CONTEXT";
      default -> "SOURCE_CONTEXT";
    };
  }

  private static String evidenceLabel(String sourceType) {
    return switch (sourceType) {
      case "SOURCE_OBJECT" -> "Source object";
      case "VALIDATION_RESULT" -> "Validation result";
      case "OPERATOR_CONTEXT" -> "Operator context";
      default -> "Source context";
    };
  }

  private static BigDecimal decimalOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
      return null;
    }
    BigDecimal value = node.decimalValue();
    return value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0
        ? value
        : null;
  }

  private static BigDecimal boundedConfidence(BigDecimal value) {
    if (value == null) {
      return null;
    }
    return value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0
        ? value
        : null;
  }

  private static String safeIdentifier(String value) {
    if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) {
      return null;
    }
    return value;
  }

  private static String safeText(String value, int maxChars) {
    if (value == null || value.isBlank() || SENSITIVE_MARKER.matcher(value).find()) {
      return null;
    }
    String stripped = value.strip();
    return stripped.length() <= maxChars
        ? stripped
        : stripped.substring(0, maxChars) + "\u2026";
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode() || !node.isValueNode()) {
      return null;
    }
    String text = node.asText();
    return text == null || text.isBlank() ? null : text;
  }

  private record ParsedPayload(
      boolean valid,
      List<AiWorkDisplayField> displayFields,
      List<AiWorkNextActionCandidate> nextActions) {
    private static ParsedPayload invalid() {
      return new ParsedPayload(false, List.of(), List.of());
    }

    private static ParsedPayload valid(
        List<AiWorkDisplayField> fields, List<AiWorkNextActionCandidate> actions) {
      return new ParsedPayload(true, List.copyOf(fields), List.copyOf(actions));
    }
  }
}
