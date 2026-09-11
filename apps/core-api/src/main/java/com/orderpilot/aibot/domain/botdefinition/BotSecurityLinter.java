package com.orderpilot.aibot.domain.botdefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * First deterministic security lint boundary for generated bot configuration.
 *
 * <p>Does not claim full prompt-injection prevention — only rejects obvious hostile fragments.
 */
public final class BotSecurityLinter {

  private static final Pattern FORBIDDEN_URL =
      Pattern.compile("(?i)(https?|ftp|file|javascript|data)\\s*:");
  private static final Pattern SHELL =
      Pattern.compile("(?i)(\\b(bash|sh|powershell|cmd\\.exe|curl|wget)\\b|\\|\\s*sh)");
  private static final Pattern SQL =
      Pattern.compile("(?i)\\b(select|insert|update|delete|drop|alter|union)\\b");
  private static final Pattern SCRIPT =
      Pattern.compile("(?i)(<script|</script>|javascript:|onerror\\s*=)");
  private static final Pattern ENCODED_SCRIPT =
      Pattern.compile("(?i)(%3cscript|\\\\u003cscript|&lt;script)");

  private BotSecurityLinter() {}

  public static List<String> lint(BotDefinitionConfiguration configuration) {
    List<String> findings = new ArrayList<>();
    if (configuration == null) {
      findings.add("configuration_missing");
      return findings;
    }
    scan(configuration.botSummary(), "botSummary", findings);
    for (BotIntentDefinition intent : configuration.intents()) {
      scan(intent.description(), "intent." + intent.intentKey(), findings);
      scan(intent.actionKey(), "action." + intent.intentKey(), findings);
      if (intent.actionKey() != null && intent.actionKey().contains("*")) {
        findings.add("wildcard_capability:" + intent.intentKey());
      }
    }
    return List.copyOf(findings);
  }

  public static void assertClean(BotDefinitionConfiguration configuration) {
    List<String> findings = lint(configuration);
    if (!findings.isEmpty()) {
      throw new IllegalArgumentException("security_lint_failed:" + findings.getFirst());
    }
  }

  private static void scan(String text, String field, List<String> findings) {
    if (text == null || text.isBlank()) {
      return;
    }
    if (FORBIDDEN_URL.matcher(text).find()) {
      findings.add("forbidden_url:" + field);
    }
    if (SHELL.matcher(text).find()) {
      findings.add("shell_fragment:" + field);
    }
    if (SQL.matcher(text).find()) {
      findings.add("sql_fragment:" + field);
    }
    if (SCRIPT.matcher(text).find() || ENCODED_SCRIPT.matcher(text).find()) {
      findings.add("script_fragment:" + field);
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.contains("secret") || lower.contains("password") || lower.contains("api_key")) {
      findings.add("secret_like_field:" + field);
    }
    if (lower.contains("tenantid")
        || lower.contains("actorid")
        || lower.contains("\"permission\"")
        || lower.contains("approvedby")
        || lower.contains("approvalstatus")
        || lower.contains("executionstatus")) {
      findings.add("authority_field:" + field);
    }
  }
}
