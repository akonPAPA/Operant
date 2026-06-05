package com.orderpilot.domain.bot;

/**
 * OP-CAP-06B policy for how the bot treats stale stock snapshots when answering availability.
 *
 * <p>The existing runtime already routes stale/missing inventory to handoff; this policy lets an
 * admin keep that behavior strict or explicitly conservative. It can never make the bot promise
 * availability from stale data.
 */
public enum InventoryFreshnessPolicy {
  /** Any snapshot older than the threshold forces handoff/review. */
  STRICT,
  /** Stale snapshot routes to handoff with a warning (default conservative behavior). */
  WARN_AND_HANDOFF,
  /** Stale snapshot may be answered only with an explicit not-a-promise warning. */
  ALLOW_WITH_WARNING
}
