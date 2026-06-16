package com.orderpilot.application.services.trust;

import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance — deterministic policy + sanitization.
 *
 * Small, deterministic, side-effect-free guard. It decides whether a memory record may be served as
 * advisory input, whether a namespace must always defer to a deterministic backend source, and rejects
 * unsafe payloads BEFORE they are ever persisted. It is intentionally conservative — it is a bounded
 * sanitization guard, not a full DLP engine. Memory is never authoritative for orders, quotes, prices,
 * stock, payments, counterparty trust, or approval status; deterministic services always win.
 */
@Service
public class AiMemoryPolicyService {
  /** Below this confidence a record is "low confidence" and excluded from search unless requested. */
  public static final BigDecimal MIN_USABLE_CONFIDENCE = new BigDecimal("0.50");

  static final int MAX_TITLE_LENGTH = 160;
  static final int MAX_SUMMARY_LENGTH = 512;
  static final int MAX_NORMALIZED_VALUE_LENGTH = 256;
  static final int MAX_SOURCE_REF_LENGTH = 160;

  /**
   * Namespaces tied to authoritative business domains (product/stock, counterparty trust, payments, trust
   * signals). For these the caller MUST prefer the deterministic backend source; memory is only a hint.
   */
  private static final Set<AiMemoryNamespace> DETERMINISTIC_PREFERRED = Set.of(
      AiMemoryNamespace.PRODUCT_ALIAS_HINT,
      AiMemoryNamespace.COUNTERPARTY_PATTERN,
      AiMemoryNamespace.PAYMENT_MATCH_HINT,
      AiMemoryNamespace.TRUST_SIGNAL_HINT);

  /** Conservative raw-payload / secret markers that must never appear in sanitized memory. */
  private static final List<String> FORBIDDEN_MARKERS = List.of(
      "begin system prompt",
      "openai_api_key",
      "authorization:",
      "password=",
      "secret=",
      "-----begin private key-----",
      "-----begin rsa private key-----",
      "bearer ");

  /** Whether a record may be served as advisory memory right now (never makes it authoritative). */
  public boolean canUseMemory(AiMemoryNamespace namespace, AiMemoryAuthorityLevel authorityLevel,
      AiMemoryStatus status, Instant expiresAt, BigDecimal confidence, Instant now) {
    if (status != AiMemoryStatus.ACTIVE) {
      return false;
    }
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      return false;
    }
    // HUMAN_APPROVED / SYSTEM_DERIVED bypass the low-confidence floor; everything else must clear it.
    if (authorityLevel == AiMemoryAuthorityLevel.HUMAN_APPROVED
        || authorityLevel == AiMemoryAuthorityLevel.SYSTEM_DERIVED) {
      return true;
    }
    return confidence != null && confidence.compareTo(MIN_USABLE_CONFIDENCE) >= 0;
  }

  /** True when a namespace must defer to the deterministic backend source of truth. */
  public boolean shouldPreferDeterministicSource(AiMemoryNamespace namespace) {
    return DETERMINISTIC_PREFERRED.contains(namespace);
  }

  /** AI memory is never the authoritative source — always false, by design. */
  public boolean isAuthoritativeUseAllowed(AiMemoryNamespace namespace) {
    return false;
  }

  /** True when a confidence value is below the usable floor (advisory only / excluded by default). */
  public boolean isLowConfidence(BigDecimal confidence) {
    return confidence == null || confidence.compareTo(MIN_USABLE_CONFIDENCE) < 0;
  }

  /**
   * Conservative payload sanitization. Throws {@link IllegalArgumentException} when a bounded field is
   * over length or contains an obvious raw-prompt/secret marker. Runs before any persistence.
   */
  public void validateMemoryPayload(String title, String summary, String normalizedValue, String sourceRef) {
    requireWithin("title", title, MAX_TITLE_LENGTH, true);
    requireWithin("summary", summary, MAX_SUMMARY_LENGTH, true);
    requireWithin("normalizedValue", normalizedValue, MAX_NORMALIZED_VALUE_LENGTH, false);
    requireWithin("sourceRef", sourceRef, MAX_SOURCE_REF_LENGTH, false);
    rejectForbidden("title", title);
    rejectForbidden("summary", summary);
    rejectForbidden("normalizedValue", normalizedValue);
    rejectForbidden("sourceRef", sourceRef);
  }

  private void requireWithin(String field, String value, int max, boolean required) {
    if (value == null || value.isBlank()) {
      if (required) {
        throw new IllegalArgumentException(field + " is required");
      }
      return;
    }
    if (value.length() > max) {
      throw new IllegalArgumentException(
          field + " exceeds the bounded length of " + max + " characters (raw payloads are not allowed)");
    }
  }

  private void rejectForbidden(String field, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    for (String marker : FORBIDDEN_MARKERS) {
      if (lower.contains(marker)) {
        throw new IllegalArgumentException(
            field + " contains a forbidden raw-prompt/secret marker and was rejected");
      }
    }
  }
}
