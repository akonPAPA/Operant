package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryHintDto;
import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryRetrievalResponse;
import com.orderpilot.domain.trust.ai.AiAdvisoryReasonCode;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemoryRecordRepository;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-19 Layer B — Advisory AI Memory Retrieval Ranking.
 *
 * Deterministic, tenant-scoped, bounded retrieval over governed OP-CAP-17F AI memory. It answers
 * "what safe, approved, tenant-scoped hints may help this task?" using rule-based scoring over indexed
 * fields — NOT vector/semantic search and NOT a learned model. Only {@code ACTIVE} (non-expired,
 * non-superseded, non-invalidated) memory is ever served; superseded/invalidated/expired records are
 * excluded by querying the {@code ACTIVE} status only. The service never mutates a record, never crosses a
 * tenant boundary, and never claims memory is authoritative — every returned hint is {@code advisoryOnly}.
 */
@Service
public class AiAdvisoryMemoryRetrievalService {
  public static final int DEFAULT_MAX_RESULTS = 10;
  static final int MIN_MAX_RESULTS = 1;
  static final int MAX_MAX_RESULTS = 25;
  /** Per-namespace fetch headroom so ranking has candidates without an unbounded scan. */
  static final int MAX_FETCH_PER_NAMESPACE = 50;
  static final int MAX_NAMESPACES = 9;
  static final BigDecimal HIGH_CONFIDENCE = new BigDecimal("0.80");

  // Score component caps (sum clamped to 0..100).
  static final int MAX_AUTHORITY = 25;
  static final int MAX_CONFIDENCE = 25;
  static final int MAX_TASK_MATCH = 20;
  static final int MAX_SOURCE_KEY = 20;
  static final int MAX_FRESHNESS = 10;

  private final AiMemoryRecordRepository records;
  private Clock clock;

  public AiAdvisoryMemoryRetrievalService(AiMemoryRecordRepository records, Clock clock) {
    this.records = records;
    this.clock = clock;
  }

  /**
   * Typed retrieval command. {@code tenantId} is resolved by the caller (from {@code TenantContext} for the
   * REST surface, or the case's tenant for the evaluation harness) — never client-supplied.
   */
  public record RetrievalCommand(
      UUID tenantId,
      AiAdvisoryTaskType taskType,
      List<AiMemoryNamespace> namespaces,
      List<AiMemorySourceType> sourceTypes,
      String subjectType,
      UUID subjectId,
      String lookupKey,
      Integer maxResults,
      BigDecimal minConfidence,
      boolean includeSuperseded,
      boolean includeInvalidated) {}

  @Transactional(readOnly = true)
  public AdvisoryMemoryRetrievalResponse retrieve(RetrievalCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    AiAdvisoryTaskType taskType = required(cmd.taskType(), "taskType");
    int maxResults = clampMaxResults(cmd.maxResults());
    BigDecimal minConfidence = effectiveMinConfidence(cmd.minConfidence());
    String lookupKey = trimToNull(cmd.lookupKey());
    Set<AiMemorySourceType> sourceTypes = cmd.sourceTypes() == null
        ? Set.of() : new LinkedHashSet<>(cmd.sourceTypes());

    List<AiMemoryNamespace> namespaces = effectiveNamespaces(taskType, cmd.namespaces());
    Instant now = clock.instant();
    int fetchPerNamespace = Math.min(maxResults * 3 + 5, MAX_FETCH_PER_NAMESPACE);
    Pageable pageable = PageRequest.of(0, fetchPerNamespace);

    List<ScoredHint> scored = new ArrayList<>();
    for (AiMemoryNamespace namespace : namespaces) {
      // status = ACTIVE excludes superseded/invalidated/expired by construction; bounded, indexed query.
      List<AiMemoryRecord> candidates = records.search(
          tenantId, namespace, AiMemoryStatus.ACTIVE, false, false, minConfidence, null, now, pageable);
      for (AiMemoryRecord record : candidates) {
        scored.add(score(record, taskType, lookupKey, sourceTypes, now));
      }
    }

    // An exact key match is the most specific possible signal for the requested lookup, so it always
    // ranks ahead of non-matching candidates; ties then fall back to the deterministic composite score.
    scored.sort(Comparator
        .comparing(ScoredHint::exactMatch).reversed()
        .thenComparing(Comparator.comparingInt(ScoredHint::score).reversed())
        .thenComparing((ScoredHint h) -> h.record().getUpdatedAt(), Comparator.reverseOrder())
        .thenComparing(h -> h.record().getId()));

    List<AdvisoryMemoryHintDto> hints = scored.stream()
        .limit(maxResults)
        .<AdvisoryMemoryHintDto>map(ScoredHint::toDto)
        .toList();

    List<String> namespaceNames = namespaces.stream().map(Enum::name).toList();
    return new AdvisoryMemoryRetrievalResponse(
        taskType.name(), namespaceNames, maxResults, hints.size(), true, hints);
  }

  // ----------------------------- scoring -----------------------------

  private record ScoredHint(
      AiMemoryRecord record, int score, boolean exactMatch, List<AiAdvisoryReasonCode> reasonCodes) {
    AdvisoryMemoryHintDto toDto() {
      return new AdvisoryMemoryHintDto(
          record.getId(), record.getNamespace().name(), record.getMemoryKey(),
          record.getSourceType().name(), record.getSourceId(), record.getAuthorityLevel().name(),
          record.getConfidence(), score, reasonCodes.stream().map(Enum::name).toList(),
          record.getSummary(), record.getCreatedAt(), record.getExpiresAt(), true);
    }
  }

  private ScoredHint score(AiMemoryRecord r, AiAdvisoryTaskType taskType, String lookupKey,
      Set<AiMemorySourceType> sourceTypes, Instant now) {
    List<AiAdvisoryReasonCode> reasons = new ArrayList<>();
    int total = 0;

    // Authority (0..25). HUMAN_APPROVED can rank highest; still advisory.
    int authority = authorityScore(r.getAuthorityLevel());
    total += authority;
    if (r.getAuthorityLevel() == AiMemoryAuthorityLevel.HUMAN_APPROVED) {
      reasons.add(AiAdvisoryReasonCode.HUMAN_APPROVED);
    } else if (r.getAuthorityLevel() == AiMemoryAuthorityLevel.SYSTEM_DERIVED) {
      reasons.add(AiAdvisoryReasonCode.SYSTEM_DERIVED);
    }

    // Confidence (0..25).
    int confidence = confidenceScore(r.getConfidence());
    total += confidence;
    if (r.getConfidence() != null && r.getConfidence().compareTo(HIGH_CONFIDENCE) >= 0) {
      reasons.add(AiAdvisoryReasonCode.HIGH_CONFIDENCE);
    }

    // Namespace/task match (0..20).
    int match = taskType.matches(r.getNamespace()) ? MAX_TASK_MATCH : 10;
    total += match;
    if (taskType.matches(r.getNamespace())) {
      reasons.add(AiAdvisoryReasonCode.TASK_NAMESPACE_MATCH);
    }

    // Source/key match (0..20). Exact key beats same-source-type.
    boolean exactMatch = lookupKey != null && lookupKey.equals(r.getMemoryKey());
    if (exactMatch) {
      total += MAX_SOURCE_KEY;
      reasons.add(AiAdvisoryReasonCode.EXACT_KEY_MATCH);
    } else if (sourceTypes.contains(r.getSourceType())) {
      total += 10;
      reasons.add(AiAdvisoryReasonCode.SAME_SOURCE_TYPE);
    }

    // Freshness (0..10).
    int freshness = freshnessScore(r.getCreatedAt(), now);
    total += freshness;
    if (freshness > 0) {
      reasons.add(AiAdvisoryReasonCode.RECENT_MEMORY);
    }

    return new ScoredHint(r, clampScore(total), exactMatch, List.copyOf(reasons));
  }

  private static int authorityScore(AiMemoryAuthorityLevel level) {
    return switch (level) {
      case HUMAN_APPROVED -> 25;
      case SYSTEM_DERIVED -> 20;
      case HIGH -> 18;
      case MEDIUM -> 12;
      case LOW -> 6;
    };
  }

  private static int confidenceScore(BigDecimal confidence) {
    if (confidence == null) {
      return 0;
    }
    int value = confidence.multiply(BigDecimal.valueOf(MAX_CONFIDENCE)).setScale(0, RoundingMode.HALF_UP).intValue();
    return Math.max(0, Math.min(MAX_CONFIDENCE, value));
  }

  private static int freshnessScore(Instant createdAt, Instant now) {
    if (createdAt == null) {
      return 0;
    }
    long days = Duration.between(createdAt, now).toDays();
    if (days < 0) {
      days = 0;
    }
    if (days <= 7) {
      return MAX_FRESHNESS;
    }
    if (days <= 30) {
      return 6;
    }
    if (days <= 90) {
      return 3;
    }
    return 0;
  }

  // ----------------------------- helpers -----------------------------

  private static List<AiMemoryNamespace> effectiveNamespaces(
      AiAdvisoryTaskType taskType, List<AiMemoryNamespace> requested) {
    Set<AiMemoryNamespace> set = new LinkedHashSet<>();
    if (requested != null && !requested.isEmpty()) {
      requested.stream().filter(java.util.Objects::nonNull).limit(MAX_NAMESPACES).forEach(set::add);
    } else {
      set.addAll(taskType.relevantNamespaces());
    }
    return List.copyOf(set);
  }

  static int clampMaxResults(Integer requested) {
    if (requested == null || requested <= 0) {
      return DEFAULT_MAX_RESULTS;
    }
    return Math.max(MIN_MAX_RESULTS, Math.min(requested, MAX_MAX_RESULTS));
  }

  private static BigDecimal effectiveMinConfidence(BigDecimal requested) {
    if (requested == null) {
      return AiMemoryPolicyService.MIN_USABLE_CONFIDENCE;
    }
    BigDecimal clamped = requested;
    if (clamped.signum() < 0) {
      clamped = BigDecimal.ZERO;
    }
    if (clamped.compareTo(BigDecimal.ONE) > 0) {
      clamped = BigDecimal.ONE;
    }
    return clamped;
  }

  private static int clampScore(int value) {
    return Math.max(0, Math.min(100, value));
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
