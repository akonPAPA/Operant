package com.orderpilot.domain.bot;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotConversationRepository extends JpaRepository<BotConversation, UUID> {
  Optional<BotConversation> findByTenantIdAndChannelAndExternalChatId(UUID tenantId, String channel, String externalChatId);
  Optional<BotConversation> findByIdAndTenantId(UUID id, UUID tenantId);
  List<BotConversation> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
}
