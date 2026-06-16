package com.orderpilot.domain.trust;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Bounded candidate metadata supplied to {@code DocumentTrustService} for a deterministic trust
 * evaluation. Carries only scalar metadata required by the trust checks — never raw document text or
 * full document payloads.
 *
 * <p>{@code contentHashInput} is a caller-provided canonical metadata/hash string; the fingerprint
 * service hashes it (SHA-256) and stores only the hash. {@code idempotencyKey} is an optional caller
 * token used to collapse repeat evaluations onto a single active run. Counterparty identity fields
 * are used solely for the in-memory mismatch comparison and are never persisted in the trust
 * tables. {@code fileSizeBytes} is a {@code Long} and {@code pageCount} an {@code Integer} bounded
 * metadata value — never raw or large OCR text.</p>
 */
public record DocumentTrustCandidate(
    String idempotencyKey,
    Instant documentDate,
    Instant issueDate,
    Instant dueDate,
    String contentHashInput,
    Long fileSizeBytes,
    Integer pageCount,
    String bankAccountHolderName,
    String expectedAccountHolderName,
    BigDecimal criticalFieldOcrConfidence,
    BigDecimal declaredTotal,
    BigDecimal computedLineItemsTotal) {
}
