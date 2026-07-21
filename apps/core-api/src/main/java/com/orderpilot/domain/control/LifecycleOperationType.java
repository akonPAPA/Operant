package com.orderpilot.domain.control;

/**
 * P1-E2A - the closed set of durable lifecycle-operation types. This slice implements exactly one type
 * ({@link #BACKUP}); RESTORE, UPGRADE and ROLLBACK are deliberately NOT present. There is no
 * {@code CUSTOM} and no client-selected operation type: the type is fixed by the route the caller hits,
 * never resolved from a request field.
 */
public enum LifecycleOperationType {
  BACKUP
}
