package com.orderpilot.application.services.runtime;

/**
 * Requested runtime execution mode resolved by the backend before admission control. It is a routing
 * preference only; runtime control may promote synchronous requests to async when cost/backpressure
 * policy requires it.
 */
public enum RuntimeExecutionMode {
  SYNC,
  ASYNC
}
