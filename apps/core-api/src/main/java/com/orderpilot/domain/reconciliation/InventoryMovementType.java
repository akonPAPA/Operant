package com.orderpilot.domain.reconciliation;

public enum InventoryMovementType {
  OPENING_STOCK,
  PURCHASE_RECEIVED,
  RETURN_IN,
  SALE,
  RETURN_OUT,
  WRITE_OFF,
  TRANSFER_OUT,
  TRANSFER_IN,
  MANUAL_ADJUSTMENT,
  ACTUAL_STOCK_COUNT
}
