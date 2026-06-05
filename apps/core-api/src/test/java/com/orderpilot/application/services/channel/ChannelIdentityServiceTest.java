package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelIdentity;
import com.orderpilot.domain.channel.ChannelIdentityRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.customer.CustomerContact;
import com.orderpilot.domain.customer.CustomerContactRepository;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChannelIdentityService.class, AuditEventService.class, CoreConfiguration.class})
class ChannelIdentityServiceTest {
  @Autowired private ChannelIdentityService service;
  @Autowired private ChannelIdentityRepository repository;
  @Autowired private CustomerAccountRepository customerAccountRepository;
  @Autowired private CustomerContactRepository customerContactRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void findOrCreateUnlinkedIdentityDeduplicatesByTenantChannelAndSender() {
    TenantContext.setTenantId(UUID.randomUUID());

    var first = service.findOrCreateUnlinkedIdentity(ChannelType.WHATSAPP, "77001112233", "conv-1", "77001112233", "Buyer One");
    var second = service.findOrCreateUnlinkedIdentity(ChannelType.WHATSAPP, "77001112233", "conv-1", "77001112233", "Buyer One");

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(repository.count()).isEqualTo(1);
    assertThat(first.getIdentityStatus()).isEqualTo("UNLINKED");
  }

  @Test
  void linkUnlinkAndBlockAreTenantScopedAndAuditCompatibleWithoutChangeRequests() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID tenantId = TenantContext.requireTenantId();
    UUID accountId = seedCustomerAccount(tenantId);
    UUID contactId = seedCustomerContact(tenantId, accountId);
    UUID actorId = UUID.randomUUID();
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-1", "chat-1", null, "Telegram User");

    var linked = service.linkIdentity(identity.getId(), accountId, contactId, actorId, "operator matched");
    assertThat(linked.getIdentityStatus()).isEqualTo("LINKED");
    assertThat(linked.getCustomerAccountId()).isEqualTo(accountId);
    assertThat(linked.getCustomerContactId()).isEqualTo(contactId);

    var unlinked = service.unlinkIdentity(identity.getId(), "wrong customer");
    assertThat(unlinked.getIdentityStatus()).isEqualTo("UNLINKED");
    assertThat(unlinked.getCustomerAccountId()).isNull();

    var blocked = service.blockIdentity(identity.getId(), "spam");
    assertThat(blocked.getIdentityStatus()).isEqualTo("BLOCKED");
    assertThat(auditEventRepository.findAll()).extracting("action")
        .contains("CHANNEL_IDENTITY_LINKED", "CHANNEL_IDENTITY_UNLINKED", "CHANNEL_IDENTITY_BLOCKED");
    assertThat(changeRequestRepository.count()).isZero();
  }

  // OP-CAP-06D: markNeedsReview

  @Test
  void markNeedsReviewSetsStatusAndEmitsAudit() {
    TenantContext.setTenantId(UUID.randomUUID());
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-nr", null, null, "User");

    var result = service.markNeedsReview(identity.getId(), "ambiguous match found");

    assertThat(result.getIdentityStatus()).isEqualTo("NEEDS_REVIEW");
    assertThat(auditEventRepository.findAll()).extracting("action")
        .contains("CHANNEL_IDENTITY_NEEDS_REVIEW");
    assertThat(changeRequestRepository.count()).isZero();
  }

  @Test
  void markNeedsReviewAuditContainsSafeMetadataOnly() {
    TenantContext.setTenantId(UUID.randomUUID());
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-nr-meta", null, null, "User");

    service.markNeedsReview(identity.getId(), "review needed");

    var auditEvents = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_NEEDS_REVIEW".equals(e.getAction())
            && e.getMetadata().contains(identity.getId().toString()))
        .toList();
    assertThat(auditEvents).hasSize(1);
    String metadata = auditEvents.get(0).getMetadata();
    assertThat(metadata).contains("channelIdentityId");
    assertThat(metadata).contains("previousStatus");
    assertThat(metadata).contains("newStatus");
    assertThat(metadata).doesNotContain("senderPhone");
    assertThat(metadata).doesNotContain("senderDisplayName");
  }

  // OP-CAP-06D: idempotency

  @Test
  void blockAlreadyBlockedIsIdempotent() {
    TenantContext.setTenantId(UUID.randomUUID());
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-block2", null, null, "User");
    service.blockIdentity(identity.getId(), "first block");
    long auditCountAfterFirst = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_BLOCKED".equals(e.getAction())).count();

    var result = service.blockIdentity(identity.getId(), "second block");

    assertThat(result.getIdentityStatus()).isEqualTo("BLOCKED");
    long auditCountAfterSecond = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_BLOCKED".equals(e.getAction())).count();
    assertThat(auditCountAfterSecond).isEqualTo(auditCountAfterFirst);
  }

  @Test
  void unlinkAlreadyUnlinkedIsIdempotent() {
    TenantContext.setTenantId(UUID.randomUUID());
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-unlink2", null, null, "User");

    var result = service.unlinkIdentity(identity.getId(), "redundant unlink");

    assertThat(result.getIdentityStatus()).isEqualTo("UNLINKED");
    assertThat(auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_UNLINKED".equals(e.getAction())).count()).isZero();
  }

  @Test
  void linkSameAccountContactIsIdempotent() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID tenantId = TenantContext.requireTenantId();
    UUID accountId = seedCustomerAccount(tenantId);
    UUID contactId = seedCustomerContact(tenantId, accountId);
    UUID actorId = UUID.randomUUID();
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-link2", null, null, "User");
    service.linkIdentity(identity.getId(), accountId, contactId, actorId, "first link");
    long auditCountAfterFirst = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_LINKED".equals(e.getAction())).count();

    var result = service.linkIdentity(identity.getId(), accountId, contactId, actorId, "repeated link");

    assertThat(result.getIdentityStatus()).isEqualTo("LINKED");
    assertThat(result.getCustomerAccountId()).isEqualTo(accountId);
    long auditCountAfterSecond = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_LINKED".equals(e.getAction())).count();
    assertThat(auditCountAfterSecond).isEqualTo(auditCountAfterFirst);
  }

  @Test
  void markNeedsReviewAlreadyNeedsReviewIsIdempotent() {
    TenantContext.setTenantId(UUID.randomUUID());
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-nr2", null, null, "User");
    service.markNeedsReview(identity.getId(), "first");
    long auditCountAfterFirst = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_NEEDS_REVIEW".equals(e.getAction())).count();

    var result = service.markNeedsReview(identity.getId(), "second");

    assertThat(result.getIdentityStatus()).isEqualTo("NEEDS_REVIEW");
    long auditCountAfterSecond = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_NEEDS_REVIEW".equals(e.getAction())).count();
    assertThat(auditCountAfterSecond).isEqualTo(auditCountAfterFirst);
  }

  // OP-CAP-06D.1: contact/account tenant validation

  @Test
  void linkWithContactOnlyDerivesAccountAndSucceeds() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID tenantId = TenantContext.requireTenantId();
    UUID accountId = seedCustomerAccount(tenantId);
    UUID contactId = seedCustomerContact(tenantId, accountId);
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-co", null, null, "User");

    // Contact-only link — accountId derived from validated contact
    var result = service.linkIdentity(identity.getId(), null, contactId, UUID.randomUUID(), "contact-only link");

    assertThat(result.getIdentityStatus()).isEqualTo("LINKED");
    assertThat(result.getCustomerContactId()).isEqualTo(contactId);
    // accountId is auto-derived from the contact
    assertThat(result.getCustomerAccountId()).isEqualTo(accountId);
    assertThat(changeRequestRepository.count()).isZero();
  }

  @Test
  void linkWithMatchingAccountAndContactSucceeds() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID tenantId = TenantContext.requireTenantId();
    UUID accountId = seedCustomerAccount(tenantId);
    UUID contactId = seedCustomerContact(tenantId, accountId);
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-match", null, null, "User");

    // Provide both — contact belongs to the given account
    var result = service.linkIdentity(identity.getId(), accountId, contactId, UUID.randomUUID(), "both provided");

    assertThat(result.getIdentityStatus()).isEqualTo("LINKED");
    assertThat(result.getCustomerAccountId()).isEqualTo(accountId);
    assertThat(result.getCustomerContactId()).isEqualTo(contactId);
  }

  @Test
  void linkWithCrossTenantContactIdIsRejected() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    TenantContext.setTenantId(tenantA);
    UUID tenantAAccountId = seedCustomerAccount(tenantA);
    UUID tenantAContactId = seedCustomerContact(tenantA, tenantAAccountId);

    TenantContext.setTenantId(tenantB);
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-xt-c", null, null, "User");

    assertThatThrownBy(() ->
        service.linkIdentity(identity.getId(), null, tenantAContactId, UUID.randomUUID(), "cross-tenant contact"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("customer_contact_id not found for this tenant");

    assertThat(repository.findByIdAndTenantId(identity.getId(), tenantB))
        .hasValueSatisfying(ci -> assertThat(ci.getIdentityStatus()).isEqualTo("UNLINKED"));
    assertThat(changeRequestRepository.count()).isZero();
  }

  @Test
  void linkWithAccountContactMismatchIsRejected() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID tenantId = TenantContext.requireTenantId();
    UUID accountA = seedCustomerAccount(tenantId);
    UUID accountB = seedCustomerAccount(tenantId);
    UUID contactForAccountA = seedCustomerContact(tenantId, accountA);
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-mismatch", null, null, "User");

    // contactForAccountA belongs to accountA but we claim it belongs to accountB
    assertThatThrownBy(() ->
        service.linkIdentity(identity.getId(), accountB, contactForAccountA, UUID.randomUUID(), "mismatch"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("customer_contact_id does not belong to the specified customer_account_id");

    assertThat(repository.findByIdAndTenantId(identity.getId(), tenantId))
        .hasValueSatisfying(ci -> assertThat(ci.getIdentityStatus()).isEqualTo("UNLINKED"));
  }

  @Test
  void linkWithContactOnlyIsIdempotent() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID tenantId = TenantContext.requireTenantId();
    UUID accountId = seedCustomerAccount(tenantId);
    UUID contactId = seedCustomerContact(tenantId, accountId);
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-co-idem", null, null, "User");
    service.linkIdentity(identity.getId(), null, contactId, UUID.randomUUID(), "first");
    long auditCountAfterFirst = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_LINKED".equals(e.getAction())).count();

    var result = service.linkIdentity(identity.getId(), null, contactId, UUID.randomUUID(), "repeated");

    assertThat(result.getIdentityStatus()).isEqualTo("LINKED");
    assertThat(result.getCustomerContactId()).isEqualTo(contactId);
    long auditCountAfterSecond = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_LINKED".equals(e.getAction())).count();
    assertThat(auditCountAfterSecond).isEqualTo(auditCountAfterFirst);
  }

  // OP-CAP-06D: cross-tenant account rejection (preserved)

  @Test
  void linkWithCrossTenantCustomerAccountIdIsRejected() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    TenantContext.setTenantId(tenantA);
    UUID tenantAAccountId = seedCustomerAccount(tenantA);

    TenantContext.setTenantId(tenantB);
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-xt", null, null, "User");

    assertThatThrownBy(() ->
        service.linkIdentity(identity.getId(), tenantAAccountId, null, UUID.randomUUID(), "cross-tenant attempt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("customer_account_id not found for this tenant");

    assertThat(repository.findByIdAndTenantId(identity.getId(), tenantB))
        .hasValueSatisfying(ci -> assertThat(ci.getIdentityStatus()).isEqualTo("UNLINKED"));
    assertThat(changeRequestRepository.count()).isZero();
  }

  // OP-CAP-06D: audit metadata contains safe fields

  @Test
  void linkAuditContainsSafeMetadata() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID tenantId = TenantContext.requireTenantId();
    UUID accountId = seedCustomerAccount(tenantId);
    UUID contactId = seedCustomerContact(tenantId, accountId);
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-audit", null, null, "User");

    service.linkIdentity(identity.getId(), accountId, contactId, UUID.randomUUID(), "link audit test");

    var auditEvents = auditEventRepository.findAll().stream()
        .filter(e -> "CHANNEL_IDENTITY_LINKED".equals(e.getAction())
            && e.getMetadata().contains(accountId.toString()))
        .toList();
    assertThat(auditEvents).hasSize(1);
    String metadata = auditEvents.get(0).getMetadata();
    assertThat(metadata).contains("channelIdentityId");
    assertThat(metadata).contains("channelType");
    assertThat(metadata).contains("previousStatus");
    assertThat(metadata).contains("newStatus");
    assertThat(metadata).contains(accountId.toString());
    assertThat(metadata).doesNotContain("senderPhone");
    assertThat(metadata).doesNotContain("senderDisplayName");
  }

  // OP-CAP-06D: tenant isolation

  @Test
  void tenantACannotGetTenantBIdentity() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    TenantContext.setTenantId(tenantA);
    var identityA = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-iso", null, null, "User");

    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> service.getIdentity(identityA.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Channel identity not found");
  }

  // --- helpers ---

  private UUID seedCustomerAccount(UUID tenantId) {
    CustomerAccount account = new CustomerAccount(
        tenantId, null, "ACC-" + UUID.randomUUID().toString().substring(0, 8),
        "Test Customer", null, null, "ACTIVE", "USD", null, Instant.parse("2026-06-05T00:00:00Z"));
    return customerAccountRepository.save(account).getId();
  }

  private UUID seedCustomerContact(UUID tenantId, UUID accountId) {
    CustomerContact contact = new CustomerContact(
        tenantId, accountId, "PRIMARY", "Test Contact", null, null, null, true,
        Instant.parse("2026-06-05T00:00:00Z"));
    return customerContactRepository.save(contact).getId();
  }
}
