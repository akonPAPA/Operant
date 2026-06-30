package com.orderpilot.application.services.intake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Local/test default: no external scanner configured. */
@Service
@ConditionalOnProperty(
    name = "orderpilot.intake.malware-scan.mode",
    havingValue = "pass-through",
    matchIfMissing = true)
public class PassThroughFileThreatScanService implements FileThreatScanService {

  @Override
  public Verdict scan(byte[] bytes, String canonicalContentType) {
    return Verdict.CLEAN;
  }
}
