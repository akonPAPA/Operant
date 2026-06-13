package com.orderpilot.application.services.trust;

import com.orderpilot.domain.trust.DocumentTrustDecision;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustSignalSeverity;
import com.orderpilot.domain.trust.TrustSignalSpec;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Deterministic, explainable mapping from a set of trust signals to a {@link DocumentTrustDecision}.
 * No model output and no historical "trust discount" can lower the result: the risk level is the
 * maximum implied by any individual signal, plus any forced-critical combination. The numeric
 * {@code riskScore} is the clamped (0..100) sum of per-severity weights.
 *
 * <ul>
 *   <li>LOW — no material (WARNING+) signals.</li>
 *   <li>MEDIUM — at least one WARNING signal, nothing stronger.</li>
 *   <li>HIGH — at least one HIGH severity signal (strong identity/payment/date-consistency signal).</li>
 *   <li>CRITICAL — at least one CRITICAL severity signal, or a forced-critical combination.</li>
 * </ul>
 */
@Component
public class DocumentTrustDecisionPolicy {
  private static final int WEIGHT_INFO = 0;
  private static final int WEIGHT_WARNING = 20;
  private static final int WEIGHT_HIGH = 45;
  private static final int WEIGHT_CRITICAL = 90;

  public DocumentTrustDecision decide(List<TrustSignalSpec> signals) {
    TrustRiskLevel risk = TrustRiskLevel.LOW;
    int rawScore = 0;
    for (TrustSignalSpec signal : signals) {
      risk = risk.max(severityToRisk(signal.severity()));
      rawScore += severityWeight(signal.severity());
    }
    if (isForcedCritical(signals)) {
      risk = risk.max(TrustRiskLevel.CRITICAL);
      rawScore = Math.max(rawScore, WEIGHT_CRITICAL);
    }
    // clampScore guards both overflow above 100 and any defensive negative.
    return DocumentTrustDecision.of(risk, DocumentTrustDecision.clampScore(rawScore));
  }

  private TrustRiskLevel severityToRisk(TrustSignalSeverity severity) {
    return switch (severity) {
      case INFO -> TrustRiskLevel.LOW;
      case WARNING -> TrustRiskLevel.MEDIUM;
      case HIGH -> TrustRiskLevel.HIGH;
      case CRITICAL -> TrustRiskLevel.CRITICAL;
    };
  }

  private int severityWeight(TrustSignalSeverity severity) {
    return switch (severity) {
      case INFO -> WEIGHT_INFO;
      case WARNING -> WEIGHT_WARNING;
      case HIGH -> WEIGHT_HIGH;
      case CRITICAL -> WEIGHT_CRITICAL;
    };
  }

  /**
   * Forced-critical combinations: pairings that individually are HIGH but together indicate a
   * coordinated payment-redirection / duplicate-submission pattern and must escalate to CRITICAL.
   */
  private boolean isForcedCritical(List<TrustSignalSpec> signals) {
    Set<TrustSignalCode> codes = signals.stream().map(TrustSignalSpec::code).collect(Collectors.toSet());
    return codes.contains(TrustSignalCode.DUPLICATE_DOCUMENT_HASH)
        && codes.contains(TrustSignalCode.BANK_ACCOUNT_HOLDER_MISMATCH);
  }
}
