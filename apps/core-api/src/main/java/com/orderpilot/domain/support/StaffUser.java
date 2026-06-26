package com.orderpilot.domain.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-51 — internal owner-company staff principal. This is a separate identity domain from tenant
 * customers/operators: a staff user is an Operant-owner-company support/maintenance person, and holding a
 * staff role grants <b>no</b> tenant access on its own. Access to a specific tenant is always mediated by a
 * scoped, reasoned, expiring {@link SupportAccessGrant}.
 *
 * <p>This is not an identity provider and not an SSO/OIDC integration: at runtime the acting staff id is
 * still carried by the trusted gateway actor header. This row exists so a staff principal can be validated
 * (exists + ACTIVE) and so the staff {@link StaffRole} bounds which support scopes may be granted.
 */
@Entity
@Table(name = "staff_user")
public class StaffUser {
  public enum Status {
    ACTIVE,
    DISABLED
  }

  @Id @GeneratedValue private UUID id;
  /** Stable internal handle (e.g. an email or login). Never a secret/credential. */
  @Column(name = "handle", nullable = false, length = 160) private String handle;
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 40) private StaffRole role;
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20) private Status status;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected StaffUser() {}

  public StaffUser(String handle, StaffRole role, Instant now) {
    this.handle = handle;
    this.role = role;
    this.status = Status.ACTIVE;
    this.createdAt = now;
  }

  public boolean isActive() {
    return status == Status.ACTIVE;
  }

  /** Whether this active staff principal's role permits the given support scope. */
  public boolean permits(StaffSupportScope scope) {
    return isActive() && role != null && role.permits(scope);
  }

  public void disable() {
    this.status = Status.DISABLED;
  }

  public UUID getId() { return id; }
  public String getHandle() { return handle; }
  public StaffRole getRole() { return role; }
  public Status getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
}
