package com.orderpilot.domain.channel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelBotRuntimeConfigurationRepository extends JpaRepository<ChannelBotRuntimeConfiguration, UUID> {
  Optional<ChannelBotRuntimeConfiguration> findByTenantIdAndChannelConnectionId(UUID tenantId, UUID channelConnectionId);

  List<ChannelBotRuntimeConfiguration> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
}
