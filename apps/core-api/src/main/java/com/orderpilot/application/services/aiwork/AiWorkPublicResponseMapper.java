package com.orderpilot.application.services.aiwork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkDisplayField;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkEvidenceItem;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkNextActionCandidate;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkSuggestionResponse;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Maps persisted AI work rows to operator-safe, typed API responses (no raw JSON strings). */
@Component
public class AiWorkPublicResponseMapper {
  private static final int MAX_DISPLAY_FIELDS = 24;
  private static final int MAX_EVIDENCE_ITEMS = 12;
  private static final int MAX_EXCERPT_CHARS = 256;
  private static final int MAX_NEXT_ACTIONS = 16;

  private final ObjectMapper objectMapper;

  public AiWorkPublicResponseMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public AiWorkSuggestionResponse toResponse(AiWorkSuggestion suggestion) {
    String summary = safeDisplayText(suggestion.getGeneratedText());
    ParsedPayload parsed = parsePayload(suggestion.getStructuredPayloadJson());
    List<AiWorkEvidenceItem> evidence = parseEvidence(suggestion.getEvidenceRefsJson());
    List<String> riskFlags = riskFlags(suggestion.getRiskLevel(), parsed);
    return new AiWorkSuggestionResponse(
        suggestion.getId(),
        suggestion.getWorkType(),
        suggestion.getSourceType(),
        suggestion.getStatus(),
        suggestion.getStrategyVersion(),
        suggestion.getRiskLevel(),
        suggestion.getConfidence(),
        summary,
        parsed.displayFields(),
        evidence,
        parsed.nextActions(),
        riskFlags,
        true,
        suggestion.getCreatedAt(),
        suggestion.getUpdatedAt(),
        suggestion.getDecidedAt(),
        suggestion.getDecisionReason());
  }

  private ParsedPayload parsePayload(String rawJson) {
    List<AiWorkDisplayField> fields = new ArrayList<>();
    List<AiWorkNextActionCandidate> nextActions = new ArrayList<>();
    if (rawJson == null || rawJson.isBlank() || containsLeakMarker(rawJson)) {
      return new ParsedPayload(fields, nextActions);
    }
    try {
      JsonNode root = objectMapper.readTree(rawJson);
      if (root.has("highlights") && root.get("highlights").isArray()) {
        for (JsonNode node : root.get("highlights")) {
          if (fields.size() >= MAX_DISPLAY_FIELDS) {
            break;
          }
          String value = boundedText(textOrNull(node));
          if (value != null) {
            fields.add(new AiWorkDisplayField("Highlight", value, null, null));
          }
        }
      }
      if (root.has("digest")) {
        String digest = boundedText(textOrNull(root.get("digest")));
        if (digest != null && fields.size() < MAX_DISPLAY_FIELDS) {
          fields.add(new AiWorkDisplayField("Digest", digest, null, null));
        }
      }
      if (root.has("explanationBasis")) {
        String basis = boundedText(textOrNull(root.get("explanationBasis")));
        if (basis != null && fields.size() < MAX_DISPLAY_FIELDS) {
          fields.add(new AiWorkDisplayField("Explanation basis", basis, null, null));
        }
      }
      if (root.has("channelSafe")) {
        fields.add(new AiWorkDisplayField(
            "Channel safe",
            root.get("channelSafe").asBoolean() ? "yes" : "no",
            null,
            null));
      }
      if (root.has("candidates") && root.get("candidates").isArray()) {
        for (JsonNode candidate : root.get("candidates")) {
          if (nextActions.size() >= MAX_NEXT_ACTIONS) {
            break;
          }
          String actionCode = boundedText(textOrNull(candidate.path("actionCode")));
          if (actionCode == null) {
            continue;
          }
          String label = boundedText(textOrNull(candidate.path("label")));
          if (label == null) {
            label = actionCode;
          }
          boolean requiresApproval = !candidate.has("requiresHumanApproval")
              || candidate.get("requiresHumanApproval").asBoolean(true);
          nextActions.add(new AiWorkNextActionCandidate(actionCode, label, requiresApproval));
        }
      }
    } catch (Exception ignored) {
      // Fail closed to empty typed projection; summary text remains available.
    }
    return new ParsedPayload(fields, nextActions);
  }

  private List<AiWorkEvidenceItem> parseEvidence(String rawJson) {
    List<AiWorkEvidenceItem> items = new ArrayList<>();
    if (rawJson == null || rawJson.isBlank() || containsLeakMarker(rawJson)) {
      return items;
    }
    try {
      JsonNode root = objectMapper.readTree(rawJson);
      if (!root.isArray()) {
        return items;
      }
      for (JsonNode node : root) {
        if (items.size() >= MAX_EVIDENCE_ITEMS) {
          break;
        }
        String type = boundedText(textOrNull(node.path("type")));
        String note = boundedText(textOrNull(node.path("note")));
        String label = type == null ? "Evidence" : type;
        items.add(new AiWorkEvidenceItem(label, note, null, null));
      }
    } catch (Exception ignored) {
      return List.of();
    }
    return items;
  }

  private static List<String> riskFlags(String riskLevel, ParsedPayload parsed) {
    List<String> flags = new ArrayList<>();
    if (riskLevel != null && !riskLevel.isBlank()) {
      flags.add("RISK_" + riskLevel.strip().toUpperCase(Locale.ROOT));
    }
    if (parsed != null) {
      for (AiWorkNextActionCandidate action : parsed.nextActions()) {
        if (action.requiresHumanApproval()) {
          flags.add("REQUIRES_HUMAN_APPROVAL");
          break;
        }
      }
    }
    return flags;
  }

  private static String safeDisplayText(String value) {
    return containsLeakMarker(value) ? "Advisory output withheld by safety filter." : value;
  }

  private static String boundedText(String value) {
    if (value == null || value.isBlank() || containsLeakMarker(value)) {
      return null;
    }
    String stripped = value.strip();
    if (stripped.length() <= MAX_EXCERPT_CHARS) {
      return stripped;
    }
    return stripped.substring(0, MAX_EXCERPT_CHARS) + "…";
  }

  private static boolean containsLeakMarker(String value) {
    if (value == null) {
      return false;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    return lower.contains("objectstoragekey")
        || lower.contains("storagekey")
        || lower.contains("rawpayload")
        || lower.contains("rawdocument")
        || lower.contains("prompttext")
        || lower.contains("stacktrace")
        || lower.contains("connectorcredential")
        || lower.contains("secret")
        || lower.contains("token");
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    String text = node.asText();
    return text == null || text.isBlank() ? null : text;
  }

  private record ParsedPayload(List<AiWorkDisplayField> displayFields, List<AiWorkNextActionCandidate> nextActions) {}
}
