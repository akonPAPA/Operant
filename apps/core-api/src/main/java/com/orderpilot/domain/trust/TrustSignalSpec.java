package com.orderpilot.domain.trust;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Transient description of a deterministic trust signal produced during evaluation, before it is
 * persisted against a {@link DocumentTrustRun}. Carries bounded evidence metadata only:
 *
 * <ul>
 *   <li>{@code fieldKey} — stable token naming the document attribute the signal is about.</li>
 *   <li>{@code pageNumber} — optional 1-based page locator (null when not page-specific).</li>
 *   <li>{@code evidenceRef} — bounded metadata reference pointer (never raw text/OCR content).</li>
 *   <li>{@code explanation} — fixed, generic explanation (never raw text or extracted values).</li>
 * </ul>
 */
public record TrustSignalSpec(
    TrustSignalCode code,
    TrustSignalSeverity severity,
    String fieldKey,
    Integer pageNumber,
    String evidenceRef,
    String explanation) {
}
