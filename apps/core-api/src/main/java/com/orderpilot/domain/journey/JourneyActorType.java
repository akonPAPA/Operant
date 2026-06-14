package com.orderpilot.domain.journey;

/** OP-CAP-22 — who produced a journey event. AI is never an authoritative actor here. */
public enum JourneyActorType {
  SYSTEM,
  OPERATOR,
  CONNECTOR,
  IMPORT
}
