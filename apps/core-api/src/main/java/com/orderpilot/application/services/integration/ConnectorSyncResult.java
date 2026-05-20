package com.orderpilot.application.services.integration;

public record ConnectorSyncResult(int recordsRead, int recordsWritten, int recordsFailed, String statusCode, String message) {
  public static ConnectorSyncResult stubbedReadOnly(String message) {
    return new ConnectorSyncResult(0, 0, 0, "ADAPTER_READY_STUB", message);
  }
}
