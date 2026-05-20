package com.orderpilot.domain.channel;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelConnectionRepository extends JpaRepository<ChannelConnection, UUID> {
  List<ChannelConnection> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  Optional<ChannelConnection> findByIdAndTenantId(UUID id, UUID tenantId);
}
