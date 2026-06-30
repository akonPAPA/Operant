package com.orderpilot.application.services.intake;

/**
 * Malware / threat scan seam. Production deployments should plug a real scanner; local/test uses a
 * pass-through implementation unless production readiness guards block it.
 */
public interface FileThreatScanService {

  enum Verdict {
    CLEAN,
    QUARANTINED,
    REJECTED
  }

  Verdict scan(byte[] bytes, String canonicalContentType);
}
