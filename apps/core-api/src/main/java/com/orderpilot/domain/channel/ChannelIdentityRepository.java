package com.orderpilot.domain.channel;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ChannelIdentityRepository extends JpaRepository<ChannelIdentity, UUID> {
  Optional<ChannelIdentity> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ChannelIdentity> findByTenantIdAndChannelTypeAndExternalSenderId(UUID tenantId, String channelType, String externalSenderId);
  List<ChannelIdentity> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

  /** Pessimistic write lock for operator mutation commands — prevents concurrent status overwrites. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<ChannelIdentity> findWithLockByIdAndTenantId(UUID id, UUID tenantId);
}
