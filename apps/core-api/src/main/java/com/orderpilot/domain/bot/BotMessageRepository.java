package com.orderpilot.domain.bot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotMessageRepository extends JpaRepository<BotMessage, UUID> {
  boolean existsByTenantIdAndChannelAndExternalChatIdAndExternalMessageId(UUID tenantId, String channel, String externalChatId, String externalMessageId);
  long countByTenantIdAndChannel(UUID tenantId, String channel);
  List<BotMessage> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  Optional<BotMessage> findByIdAndTenantId(UUID id, UUID tenantId);
  List<BotMessage> findByTenantIdAndConversationIdOrderByCreatedAtAsc(UUID tenantId, UUID conversationId);
  List<BotMessage> findByTenantIdAndConversationIdOrderByCreatedAtDesc(UUID tenantId, UUID conversationId);
}
