package com.orderpilot.domain.bot;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotHandoffRepository extends JpaRepository<BotHandoff, UUID> {
  List<BotHandoff> findByTenantIdAndConversationIdOrderByCreatedAtDesc(UUID tenantId, UUID conversationId);
  List<BotHandoff> findByTenantIdAndMessageIdOrderByCreatedAtDesc(UUID tenantId, UUID messageId);
}
