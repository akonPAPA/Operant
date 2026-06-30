package com.orderpilot.application.services.intake;

/**
 * HTTP adapter seam for an external malware scanner. Production uses a real endpoint; tests use stubs.
 */
public interface ExternalFileThreatScanClient {

  FileThreatScanService.Verdict scan(byte[] bytes, String canonicalContentType);
}
