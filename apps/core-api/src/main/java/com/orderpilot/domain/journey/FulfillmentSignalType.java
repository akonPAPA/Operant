package com.orderpilot.domain.journey;

import java.util.Optional;

/**
 * OP-CAP-22 — bounded set of fulfillment signal types.
 *
 * <p>Note: there is intentionally no payment signal type here. A fulfillment signal is structurally
 * incapable of asserting payment confirmation, so payment milestones can never be faked through this
 * path.
 */
public enum FulfillmentSignalType {
  RESERVED(MilestoneCode.FULFILLMENT_PREPARING),
  PICKING(MilestoneCode.FULFILLMENT_PREPARING),
  PACKED(MilestoneCode.PACKED),
  READY_TO_SHIP(MilestoneCode.READY_TO_SHIP),
  SHIPPED(MilestoneCode.SHIPPED),
  DELIVERED(MilestoneCode.DELIVERED),
  CANCELLED(MilestoneCode.CANCELLED),
  BLOCKED(MilestoneCode.BLOCKED_EXCEPTION),
  RETURN_REQUESTED(null);

  private final MilestoneCode milestoneCode;

  FulfillmentSignalType(MilestoneCode milestoneCode) {
    this.milestoneCode = milestoneCode;
  }

  /** The milestone this signal advances, if any. RETURN_REQUESTED maps to no canonical milestone. */
  public Optional<MilestoneCode> milestoneCode() {
    return Optional.ofNullable(milestoneCode);
  }

  public boolean isBlocking() {
    return this == BLOCKED;
  }
}
