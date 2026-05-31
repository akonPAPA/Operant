package com.orderpilot.domain.bot;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotRateLimitEventRepository extends JpaRepository<BotRateLimitEvent, UUID> {
  long countByTenantIdAndConversationKeyAndCreatedAtAfter(UUID tenantId, String conversationKey, Instant createdAfter);
  long countByTenantIdAndConversationKeyAndEventType(UUID tenantId, String conversationKey, String eventType);
}
