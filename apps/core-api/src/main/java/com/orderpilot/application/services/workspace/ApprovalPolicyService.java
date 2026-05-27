package com.orderpilot.application.services.workspace;

import com.orderpilot.domain.workspace.QuoteApprovalRequest;
import com.orderpilot.domain.workspace.QuoteApprovalRequestRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalPolicyService {
  private final QuoteApprovalRequestRepository repository;
  private final Clock clock;

  public ApprovalPolicyService(QuoteApprovalRequestRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional
  public QuoteApprovalRequest request(UUID tenantId, UUID quoteId, UUID lineId, String type, String severity, String reasonCode, String reason) {
    return repository.save(new QuoteApprovalRequest(tenantId, quoteId, lineId, type, severity, reasonCode, reason, clock.instant()));
  }
}
