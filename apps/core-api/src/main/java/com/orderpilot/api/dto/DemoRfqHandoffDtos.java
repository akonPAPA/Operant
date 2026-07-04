package com.orderpilot.api.dto;

import java.util.UUID;

/** Public contract for the bounded local-demo RFQ entrypoint. */
public final class DemoRfqHandoffDtos {
  private DemoRfqHandoffDtos() {}

  /**
   * Operator-safe result. The handoff id is the approved opaque workflow handle; source event,
   * connection, actor, audit, idempotency, provider payload, and bot-runtime identifiers stay
   * internal.
   */
  public record DemoRfqHandoffResponse(UUID handoffId, String status, String message) {}
}
