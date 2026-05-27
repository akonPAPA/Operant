package com.orderpilot.application.services;

import com.orderpilot.domain.intake.InboundEventLedgerRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InboundDeduplicationService {
  private final InboundEventLedgerRepository ledgerRepository;

  public InboundDeduplicationService(InboundEventLedgerRepository ledgerRepository) {
    this.ledgerRepository = ledgerRepository;
  }

  public boolean isDuplicateEvent(UUID tenantId, String source, String externalEventId, String fingerprintSha256) {
    if (externalEventId != null && !externalEventId.isBlank()
        && ledgerRepository.existsByTenantIdAndSourceAndExternalEventId(tenantId, source, externalEventId)) {
      return true;
    }
    return fingerprintSha256 != null && !fingerprintSha256.isBlank()
        && ledgerRepository.findFirstByTenantIdAndSourceAndFingerprintSha256OrderByReceivedAtDesc(tenantId, source, fingerprintSha256).isPresent();
  }
}
