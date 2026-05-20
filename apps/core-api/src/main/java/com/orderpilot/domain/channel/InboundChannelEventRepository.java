package com.orderpilot.domain.channel;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundChannelEventRepository extends JpaRepository<InboundChannelEvent, UUID> {
  List<InboundChannelEvent> findByTenantIdOrderByReceivedAtDesc(UUID tenantId);
  Optional<InboundChannelEvent> findFirstByTenantIdAndProviderTypeAndExternalEventId(UUID tenantId, ChannelProviderType providerType, String externalEventId);
  Optional<InboundChannelEvent> findFirstByTenantIdAndChannelConnectionIdAndPayloadHash(UUID tenantId, UUID channelConnectionId, String payloadHash);
}
