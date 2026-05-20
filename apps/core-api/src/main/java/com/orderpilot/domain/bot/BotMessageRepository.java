package com.orderpilot.domain.bot;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotMessageRepository extends JpaRepository<BotMessage, UUID> {
  boolean existsByTenantIdAndChannelAndExternalChatIdAndExternalMessageId(UUID tenantId, String channel, String externalChatId, String externalMessageId);
  long countByTenantIdAndChannel(UUID tenantId, String channel);
}
