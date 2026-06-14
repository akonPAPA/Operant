package com.orderpilot.domain.journey;

/** OP-CAP-22 — where a fulfillment signal came from. No source performs an external write. */
public enum FulfillmentSignalSource {
  INTERNAL,
  CONNECTOR_MIRROR,
  IMPORT,
  MANUAL,
  SYSTEM_DERIVED;

  /** Maps a signal source to the evidence level it confers on a derived milestone/event. */
  public EvidenceLevel evidenceLevel() {
    return switch (this) {
      case INTERNAL -> EvidenceLevel.VERIFIED;
      case CONNECTOR_MIRROR, IMPORT -> EvidenceLevel.MIRRORED;
      case MANUAL -> EvidenceLevel.MANUAL;
      case SYSTEM_DERIVED -> EvidenceLevel.SYSTEM_DERIVED;
    };
  }
}
