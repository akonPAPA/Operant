package com.orderpilot.application.services.modelruntime;

import java.util.List;

/** Safe public shape for advisory model output. */
public record AiModelAdvisoryOutput(
    List<String> findings,
    String riskClassification,
    List<String> suggestedTests,
    String confidence,
    List<String> caveats,
    String nextReviewRecommendation) {

  public AiModelAdvisoryOutput {
    findings = findings == null ? List.of() : List.copyOf(findings);
    suggestedTests = suggestedTests == null ? List.of() : List.copyOf(suggestedTests);
    caveats = caveats == null ? List.of() : List.copyOf(caveats);
  }

  public boolean advisoryOnly() {
    return true;
  }

  public boolean hasBusinessWriteAuthority() {
    return false;
  }

  public boolean canExecuteConnector() {
    return false;
  }
}
