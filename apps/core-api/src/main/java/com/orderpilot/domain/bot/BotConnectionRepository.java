package com.orderpilot.domain.bot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BotConnectionRepository extends JpaRepository<BotConnection, UUID> {
  Optional<BotConnection> findFirstByTenantIdAndChannelTypeOrderByCreatedAtDesc(UUID tenantId, String channelType);
  Optional<BotConnection> findByIdAndTenantId(UUID id, UUID tenantId);
  List<BotConnection> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from BotConnection c where c.tenantId = :tenantId and c.channelType = :channelType order by c.createdAt desc")
  List<BotConnection> findByTenantIdAndChannelTypeForUpdate(@Param("tenantId") UUID tenantId, @Param("channelType") String channelType);
}
