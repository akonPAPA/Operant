package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelIdentity;
import com.orderpilot.domain.channel.ChannelIdentityResolutionStatus;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-06C identity resolver tests. Deterministic resolution over the existing channel_identity
 * mapping; tenant-scoped; no AI; never trusts customer-supplied text.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ ChannelIdentityResolverService.class, ChannelIdentityService.class, AuditEventService.class, CoreConfiguration.class })
class ChannelIdentityResolverServiceTest {
  @Autowired private ChannelIdentityResolverService resolverService;
  @Autowired private ChannelIdentityService identityService;
  @Autowired private TenantRepository tenantRepository;
  @Autowired private CustomerAccountRepository customerAccountRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void linkedIdentityResolvesToCustomerContext() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID accountId = seedCustomerAccount(tenantId);
    ChannelIdentity identity = identityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-1", "conv-1", null, "User");
    identityService.linkIdentity(identity.getId(), accountId, null, UUID.randomUUID(), "operator linked");

    ChannelIdentityResolution resolution = resolverService.resolve(ChannelProviderType.TELEGRAM, "chat-1");

    assertThat(resolution.status()).isEqualTo(ChannelIdentityResolutionStatus.RESOLVED);
    assertThat(resolution.customerAccountId()).isEqualTo(accountId);
  }

  @Test void noMappingResolvesUnknown() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelIdentityResolution resolution = resolverService.resolve(ChannelProviderType.TELEGRAM, "chat-none");
    assertThat(resolution.status()).isEqualTo(ChannelIdentityResolutionStatus.UNKNOWN);
    assertThat(resolution.customerAccountId()).isNull();
  }

  @Test void unlinkedMappingResolvesUnknown() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    identityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-unlinked", null, null, "User");
    ChannelIdentityResolution resolution = resolverService.resolve(ChannelProviderType.TELEGRAM, "chat-unlinked");
    assertThat(resolution.status()).isEqualTo(ChannelIdentityResolutionStatus.UNKNOWN);
  }

  @Test void suggestedMatchResolvesAmbiguous() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelIdentity identity = identityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-amb", null, null, "User");
    identityService.suggestCustomerMatch(identity.getId(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("0.50"), "candidate");
    ChannelIdentityResolution resolution = resolverService.resolve(ChannelProviderType.TELEGRAM, "chat-amb");
    assertThat(resolution.status()).isEqualTo(ChannelIdentityResolutionStatus.AMBIGUOUS);
  }

  @Test void blockedIdentityResolvesBlocked() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelIdentity identity = identityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-blocked", null, null, "User");
    identityService.blockIdentity(identity.getId(), "abuse");
    ChannelIdentityResolution resolution = resolverService.resolve(ChannelProviderType.TELEGRAM, "chat-blocked");
    assertThat(resolution.status()).isEqualTo(ChannelIdentityResolutionStatus.BLOCKED);
  }

  /** OP-CAP-06D: after markNeedsReview(), resolver must see AMBIGUOUS (not UNKNOWN). */
  @Test void needsReviewResolvesAmbiguous() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelIdentity identity = identityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-nr", null, null, "User");
    identityService.markNeedsReview(identity.getId(), "operator flagged");

    ChannelIdentityResolution resolution = resolverService.resolve(ChannelProviderType.TELEGRAM, "chat-nr");

    assertThat(resolution.status()).isEqualTo(ChannelIdentityResolutionStatus.AMBIGUOUS);
    assertThat(resolution.reason()).isEqualTo("NEEDS_REVIEW");
  }

  @Test void blankSenderIsNotApplicable() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    assertThat(resolverService.resolve(ChannelProviderType.TELEGRAM, "").status()).isEqualTo(ChannelIdentityResolutionStatus.NOT_APPLICABLE);
    assertThat(resolverService.resolve(ChannelProviderType.TELEGRAM, null).status()).isEqualTo(ChannelIdentityResolutionStatus.NOT_APPLICABLE);
  }

  @Test void tenantAMappingDoesNotResolveForTenantB() {
    UUID tenantA = seedTenant();
    TenantContext.setTenantId(tenantA);
    UUID accountId = seedCustomerAccount(tenantA);
    ChannelIdentity identity = identityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-shared", null, null, "User");
    identityService.linkIdentity(identity.getId(), accountId, null, UUID.randomUUID(), "linked");

    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);
    ChannelIdentityResolution resolution = resolverService.resolve(ChannelProviderType.TELEGRAM, "chat-shared");
    assertThat(resolution.status()).isEqualTo(ChannelIdentityResolutionStatus.UNKNOWN);
  }

  private UUID seedTenant() {
    return tenantRepository.save(new Tenant("idr-" + UUID.randomUUID(), "Resolver Test", "ACTIVE", Instant.parse("2026-06-04T00:00:00Z"))).getId();
  }

  private UUID seedCustomerAccount(UUID tenantId) {
    CustomerAccount account = new CustomerAccount(
        tenantId, null, "ACC-" + UUID.randomUUID().toString().substring(0, 8),
        "Test Customer", null, null, "ACTIVE", "USD", null, Instant.parse("2026-06-05T00:00:00Z"));
    return customerAccountRepository.save(account).getId();
  }
}
