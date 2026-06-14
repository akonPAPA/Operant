package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryHintDto;
import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryRetrievalResponse;
import com.orderpilot.api.dto.AiAdvisoryRuntimeAssistDtos.RuntimeAssistHintDto;
import com.orderpilot.api.dto.AiAdvisoryRuntimeAssistDtos.RuntimeAssistResponse;
import com.orderpilot.application.services.trust.AiAdvisoryMemoryRetrievalService.RetrievalCommand;
import com.orderpilot.domain.trust.ai.AiAdvisoryReasonCode;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.RuntimeAssistContextType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-20 Layer A — AI Advisory Runtime Assist.
 *
 * Read-only runtime-assist surface that consumes OP-CAP-19 {@link AiAdvisoryMemoryRetrievalService} and
 * returns ranked, explainable, bounded advisory hints for a concrete workflow context (the first context
 * is {@link RuntimeAssistContextType#TRUST_VALIDATION_REVIEW}). It is a pure transform of the deterministic
 * OP-CAP-19 retrieval output: it preserves that ranking exactly (rank = retrieval order), adds only
 * deterministic, explainable presentation, and never re-reads memory records or business state.
 *
 * <p>Safety invariant: hints may suggest; deterministic backend services decide. This service never
 * mutates memory or business state, never approves/exports/overrides/resolves/writes trusted business
 * data, never crosses a tenant boundary, never falls back to a broad tenant-wide memory scan, and never
 * exposes raw documents/prompts/normalized values. Every hint is {@code advisoryOnly = true}.
 */
@Service
public class AiAdvisoryRuntimeAssistService {
  static final int DEFAULT_MAX_HINTS = 5;
  static final int MAX_HINTS = 15;
  static final int MAX_SUMMARY = 280;
  static final int MAX_TITLE = 160;

  /**
   * Bounded restatement of the safety invariant returned with every assist response. The deterministic
   * backend remains authoritative regardless of any hint shown here.
   */
  static final List<String> DETERMINISTIC_VALIDATION_REQUIRED = List.of(
      "Deterministic validation engine must re-check every value; advisory hints decide nothing.",
      "Human approval is required for any risky change; hints never approve, override, or resolve.",
      "No hint may export, write, or mutate orders, quotes, inventory, prices, payments, or counterparty trust.");

  private final AiAdvisoryMemoryRetrievalService retrievalService;

  public AiAdvisoryRuntimeAssistService(AiAdvisoryMemoryRetrievalService retrievalService) {
    this.retrievalService = retrievalService;
  }

  /**
   * Typed assist command. {@code tenantId} is resolved by the caller (from {@code TenantContext} for the
   * REST surface) — never client-supplied. {@code contextId} is an advisory correlation reference only; it
   * is used purely to derive an optional bounded lookup key and is never used to load business state.
   */
  public record AssistCommand(
      UUID tenantId,
      RuntimeAssistContextType contextType,
      UUID contextId,
      AiAdvisoryTaskType taskType,
      String lookupKey,
      Integer maxHints) {}

  @Transactional(readOnly = true)
  public RuntimeAssistResponse assist(AssistCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    RuntimeAssistContextType contextType = required(cmd.contextType(), "contextType");
    // Deterministic context -> task mapping; an explicit task type may pin a narrower lens.
    AiAdvisoryTaskType taskType = cmd.taskType() != null ? cmd.taskType() : contextType.defaultTaskType();
    int maxHints = clampMaxHints(cmd.maxHints());
    String lookupKey = trimToNull(cmd.lookupKey());

    // Delegate to OP-CAP-19 deterministic, tenant-scoped, bounded retrieval. namespaces=null lets the task
    // type drive its bounded relevant namespaces — never a broad tenant-wide scan.
    AdvisoryMemoryRetrievalResponse retrieval = retrievalService.retrieve(new RetrievalCommand(
        tenantId, taskType, null, List.of(), null, cmd.contextId(), lookupKey,
        maxHints, null, false, false));

    List<AdvisoryMemoryHintDto> source = retrieval.hints();
    List<RuntimeAssistHintDto> hints = new java.util.ArrayList<>(source.size());
    int rank = 1;
    for (AdvisoryMemoryHintDto hint : source) {
      hints.add(toAssistHint(hint, taskType, rank++));
    }

    return new RuntimeAssistResponse(
        contextType.name(), cmd.contextId(), taskType.name(), maxHints, hints.size(), true,
        DETERMINISTIC_VALIDATION_REQUIRED, List.copyOf(hints));
  }

  // ----------------------------- presentation (deterministic, explainable) -----------------------------

  private RuntimeAssistHintDto toAssistHint(AdvisoryMemoryHintDto h, AiAdvisoryTaskType taskType, int rank) {
    return new RuntimeAssistHintDto(
        UUID.nameUUIDFromBytes((h.memoryRecordId() + ":runtime-assist").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        h.memoryRecordId(),
        taskType.name(),
        h.reasonCodes(),
        rank,
        h.score(),
        h.confidence(),
        bound(title(h), MAX_TITLE),
        bound(h.summary(), MAX_SUMMARY),
        evidenceSummary(h.reasonCodes()),
        sourceAuthority(h.authority()),
        applicability(h.reasonCodes()),
        "ADVISORY_ONLY",
        true,
        h.createdAt());
  }

  /** Bounded title derived from already-safe metadata; never raw content. */
  private static String title(AdvisoryMemoryHintDto h) {
    return h.namespace() + " · " + h.memoryKey();
  }

  /** Explainability: why this hint was selected, from the deterministic OP-CAP-19 reason codes. */
  private static String evidenceSummary(List<String> reasonCodes) {
    if (reasonCodes == null || reasonCodes.isEmpty()) {
      return "Selected by deterministic advisory ranking.";
    }
    return bound("Selected because: " + String.join(", ", reasonCodes) + ".", MAX_SUMMARY);
  }

  /** Source authority always names the advisory memory origin — never claims business truth. */
  private static String sourceAuthority(String authority) {
    return "ADVISORY_MEMORY/" + (authority == null ? "UNKNOWN" : authority);
  }

  /** Deterministic applicability classification from the strongest matching reason code. */
  private static String applicability(List<String> reasonCodes) {
    if (reasonCodes == null) {
      return "GENERAL_ADVISORY";
    }
    if (reasonCodes.contains(AiAdvisoryReasonCode.EXACT_KEY_MATCH.name())) {
      return "DIRECT_CONTEXT_MATCH";
    }
    if (reasonCodes.contains(AiAdvisoryReasonCode.SAME_SOURCE_TYPE.name())) {
      return "SAME_SOURCE_TYPE";
    }
    if (reasonCodes.contains(AiAdvisoryReasonCode.TASK_NAMESPACE_MATCH.name())) {
      return "TASK_RELEVANT";
    }
    return "GENERAL_ADVISORY";
  }

  // ----------------------------- helpers -----------------------------

  static int clampMaxHints(Integer requested) {
    if (requested == null || requested <= 0) {
      return DEFAULT_MAX_HINTS;
    }
    return Math.min(requested, MAX_HINTS);
  }

  private static String bound(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
