package com.orderpilot.application.services.aiwork;

import java.math.BigDecimal;

/**
 * OP-CAP-07A advisory output of an {@link AiWorkProvider}.
 *
 * <p>This is untrusted advisory content. {@code structuredPayloadJson} and {@code evidenceRefsJson}
 * are JSON strings persisted into JSONB columns. {@code riskLevel} is one of LOW/MEDIUM/HIGH and is
 * surfaced to the operator so risky suggestions are visibly flagged for human judgment.
 */
public record AiWorkGenerationResult(
    String generatedText,
    String structuredPayloadJson,
    String evidenceRefsJson,
    String riskLevel,
    BigDecimal confidence,
    String strategyVersion) {}
