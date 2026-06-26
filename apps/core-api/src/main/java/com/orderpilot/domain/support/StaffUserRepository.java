package com.orderpilot.domain.support;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-51 — lookup for the internal staff principal. Staff users are platform-internal (no tenant
 * column): a staff identity is global, but it confers no tenant access without a {@link SupportAccessGrant}.
 */
public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {
  Optional<StaffUser> findByHandle(String handle);
}
