package com.orderpilot.domain.intake;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ChannelMessageRepository extends JpaRepository<ChannelMessage, UUID> {
  List<ChannelMessage> findByTenantIdOrderByReceivedAtDesc(UUID tenantId);
  Optional<ChannelMessage> findByIdAndTenantId(UUID id, UUID tenantId);
  List<ChannelMessage> findByTenantIdAndIdIn(UUID tenantId, List<UUID> ids);
  List<ChannelMessage> findByTenantIdAndConversationIdOrderByReceivedAt(UUID tenantId, String conversationId);
  Optional<ChannelMessage> findFirstByTenantIdAndChannelAndExternalMessageId(UUID tenantId, String channel, String externalMessageId);
}
