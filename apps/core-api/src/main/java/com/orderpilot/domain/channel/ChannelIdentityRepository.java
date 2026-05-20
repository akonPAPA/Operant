package com.orderpilot.domain.channel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelIdentityRepository extends JpaRepository<ChannelIdentity, UUID> {
  Optional<ChannelIdentity> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ChannelIdentity> findByTenantIdAndChannelTypeAndExternalSenderId(UUID tenantId, String channelType, String externalSenderId);
  List<ChannelIdentity> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
}
