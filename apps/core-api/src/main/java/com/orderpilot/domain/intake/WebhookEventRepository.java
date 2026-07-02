package com.orderpilot.domain.intake;
import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository;
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
  List<WebhookEvent> findByTenantIdOrderByReceivedAtDesc(UUID tenantId);
  List<WebhookEvent> findByTenantIdOrderByReceivedAtDesc(UUID tenantId, Pageable pageable);
  Optional<WebhookEvent> findByIdAndTenantId(UUID id, UUID tenantId);
  boolean existsByTenantIdAndProviderAndExternalEventId(UUID tenantId, String provider, String externalEventId);
}
