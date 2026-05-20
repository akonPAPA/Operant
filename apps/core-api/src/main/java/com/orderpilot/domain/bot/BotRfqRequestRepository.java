package com.orderpilot.domain.bot;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotRfqRequestRepository extends JpaRepository<BotRfqRequest, UUID> {
  long countByTenantId(UUID tenantId);
  Optional<BotRfqRequest> findByIdAndTenantId(UUID id, UUID tenantId);
}
