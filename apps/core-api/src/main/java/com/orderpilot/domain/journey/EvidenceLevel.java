package com.orderpilot.domain.journey;

/**
 * OP-CAP-22 — how trustworthy the evidence behind a milestone/event is.
 *
 * <p>Authority order (highest first): VERIFIED &gt; MIRRORED &gt; SYSTEM_DERIVED &gt; ESTIMATED &gt;
 * MANUAL is treated as operator-attested. UNKNOWN means no evidence is available — the surface must
 * say so honestly rather than guess.
 */
public enum EvidenceLevel {
  VERIFIED,
  MIRRORED,
  SYSTEM_DERIVED,
  ESTIMATED,
  MANUAL,
  UNKNOWN
}
