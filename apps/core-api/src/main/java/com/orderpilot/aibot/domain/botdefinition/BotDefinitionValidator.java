package com.orderpilot.aibot.domain.botdefinition;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.orderpilot.aibot.domain.capability.CapabilityRegistry;

/**
 * Deterministic validator for declarative bot configuration. Rejects capability escalation,
 * unknown intents, and executable/script-like content.
 */
public final class BotDefinitionValidator {

  public static final int MAX_INTENTS = 20;
  public static final int MAX_SUMMARY_CHARS = 2000;
  public static final int MAX_DESCRIPTION_CHARS = 500;

  private static final Pattern URL_SCHEME =
      Pattern.compile("(?i)(https?|ftp|file|javascript|data)\\s*:");
  private static final Pattern SQL_FRAGMENT =
      Pattern.compile("(?i)\\b(select|insert|update|delete|drop|alter|truncate|union)\\b.*\\b(from|into|table|set)\\b");
  private static final Pattern SHELL_FRAGMENT =
      Pattern.compile("(?i)(\\b(curl|wget|bash|sh|powershell|cmd)\\b|\\|\\s*sh|;\\s*rm\\b)");
  private static final Pattern SCRIPT_FRAGMENT =
      Pattern.compile("(?i)(<script|javascript:|eval\\s*\\(|Function\\s*\\()");

  private BotDefinitionValidator() {}

  public static void validate(BotDefinitionConfiguration configuration) {
    if (configuration == null) {
      throw new IllegalArgumentException("configuration_required");
    }
    if (!BotDefinitionConfiguration.SCHEMA_V1.equals(configuration.schemaVersion())) {
      throw new IllegalArgumentException("unknown_schema_version");
    }
    String summary = configuration.botSummary() == null ? "" : configuration.botSummary();
    if (summary.length() > MAX_SUMMARY_CHARS) {
      throw new IllegalArgumentException("bot_summary_too_long");
    }
    rejectHostileText(summary, "bot_summary");

    List<BotIntentDefinition> intents = configuration.intents();
    if (intents.size() > MAX_INTENTS) {
      throw new IllegalArgumentException("too_many_intents");
    }
    Set<String> seen = new HashSet<>();
    for (BotIntentDefinition intent : intents) {
      validateIntent(intent, seen);
    }
  }

  private static void validateIntent(BotIntentDefinition intent, Set<String> seen) {
    if (!BotIntentKey.isKnown(intent.intentKey())) {
      throw new IllegalArgumentException("unknown_intent_key");
    }
    if (!seen.add(intent.intentKey())) {
      throw new IllegalArgumentException("duplicate_intent_key");
    }
    if (intent.description().length() > MAX_DESCRIPTION_CHARS) {
      throw new IllegalArgumentException("intent_description_too_long");
    }
    rejectHostileText(intent.description(), "intent_description");
    BigDecimal confidence = intent.minimumConfidence();
    if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("invalid_confidence");
    }
    if (!CapabilityRegistry.isAllowed(intent.actionKey())) {
      throw new IllegalArgumentException("unknown_action_key");
    }
    String policy = intent.responsePolicyKey().toUpperCase(Locale.ROOT);
    if (!"SAFE_PLAIN_TEXT".equals(policy) && !"HANDOFF_PLAIN_TEXT".equals(policy)) {
      throw new IllegalArgumentException("unknown_response_policy");
    }
  }

  static void rejectHostileText(String text, String field) {
    if (text == null || text.isBlank()) {
      return;
    }
    if (URL_SCHEME.matcher(text).find()) {
      throw new IllegalArgumentException(field + "_url_forbidden");
    }
    if (SQL_FRAGMENT.matcher(text).find()) {
      throw new IllegalArgumentException(field + "_sql_forbidden");
    }
    if (SHELL_FRAGMENT.matcher(text).find()) {
      throw new IllegalArgumentException(field + "_shell_forbidden");
    }
    if (SCRIPT_FRAGMENT.matcher(text).find()) {
      throw new IllegalArgumentException(field + "_script_forbidden");
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.contains("tenantid")
        || lower.contains("actorid")
        || lower.contains("permission")
        || lower.contains("apikey")
        || lower.contains("api_key")
        || lower.contains("approvedby")) {
      throw new IllegalArgumentException(field + "_authority_field_forbidden");
    }
  }
}
