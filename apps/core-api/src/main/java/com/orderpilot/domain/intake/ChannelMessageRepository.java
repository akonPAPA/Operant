package com.orderpilot.domain.intake;
import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository;
public interface ChannelMessageRepository extends JpaRepository<ChannelMessage, UUID> {
  List<ChannelMessage> findByTenantIdOrderByReceivedAtDesc(UUID tenantId);
  List<ChannelMessage> findByTenantIdOrderByReceivedAtDesc(UUID tenantId, Pageable pageable);
  Optional<ChannelMessage> findByIdAndTenantId(UUID id, UUID tenantId);
  List<ChannelMessage> findByTenantIdAndIdIn(UUID tenantId, List<UUID> ids);
  List<ChannelMessage> findByTenantIdAndConversationIdOrderByReceivedAt(UUID tenantId, String conversationId);
  List<ChannelMessage> findByTenantIdAndConversationIdOrderByReceivedAt(UUID tenantId, String conversationId, Pageable pageable);
  Optional<ChannelMessage> findFirstByTenantIdAndChannelAndExternalMessageId(UUID tenantId, String channel, String externalMessageId);
}
