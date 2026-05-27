package com.orderpilot.domain.intake;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
  List<WebhookEvent> findByTenantIdOrderByReceivedAtDesc(UUID tenantId);
  Optional<WebhookEvent> findByIdAndTenantId(UUID id, UUID tenantId);
  boolean existsByProviderAndExternalEventId(String provider, String externalEventId);
  boolean existsByTenantIdAndProviderAndExternalEventId(UUID tenantId, String provider, String externalEventId);
}
