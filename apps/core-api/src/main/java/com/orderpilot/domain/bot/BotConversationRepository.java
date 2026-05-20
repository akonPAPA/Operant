package com.orderpilot.domain.bot;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotConversationRepository extends JpaRepository<BotConversation, UUID> {
  Optional<BotConversation> findByTenantIdAndChannelAndExternalChatId(UUID tenantId, String channel, String externalChatId);
}
