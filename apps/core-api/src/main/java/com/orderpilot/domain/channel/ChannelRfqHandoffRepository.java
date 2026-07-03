package com.orderpilot.domain.channel;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ChannelRfqHandoffRepository extends JpaRepository<ChannelRfqHandoff, UUID> {

  Optional<ChannelRfqHandoff> findByIdAndTenantId(UUID id, UUID tenantId);

  /**
   * OP-CAP-06C: pessimistic write lock for operator transition commands — prevents two concurrent
   * operators from racing a status change (e.g. dismiss vs. mark-converted) on the same handoff.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<ChannelRfqHandoff> findWithLockByIdAndTenantId(UUID id, UUID tenantId);

  /** Idempotency guard: one handoff per source channel event within a tenant. */
  Optional<ChannelRfqHandoff> findFirstByTenantIdAndInboundChannelEventId(UUID tenantId, UUID inboundChannelEventId);

  List<ChannelRfqHandoff> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  List<ChannelRfqHandoff> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, ChannelRfqHandoffStatus status);

  // PR#236: tie-stable bounded ordering (createdAt desc, id desc). The secondary id key gives a
  // deterministic total order so handoffs sharing the same createdAt cannot overlap or reorder
  // across pages under large-tenant reads. These are the only bounded list methods the service uses.
  List<ChannelRfqHandoff> findByTenantIdOrderByCreatedAtDescIdDesc(
      UUID tenantId, Pageable pageable);

  List<ChannelRfqHandoff> findByTenantIdAndStatusOrderByCreatedAtDescIdDesc(
      UUID tenantId, ChannelRfqHandoffStatus status, Pageable pageable);
}
