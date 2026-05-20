package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelIdentityRepository;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
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
    UUID customerAccountId = UUID.randomUUID();
    UUID customerContactId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    var identity = service.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-1", "chat-1", null, "Telegram User");

    var linked = service.linkIdentity(identity.getId(), customerAccountId, customerContactId, actorId, "operator matched");
    assertThat(linked.getIdentityStatus()).isEqualTo("LINKED");
    assertThat(linked.getCustomerAccountId()).isEqualTo(customerAccountId);
    assertThat(linked.getCustomerContactId()).isEqualTo(customerContactId);

    var unlinked = service.unlinkIdentity(identity.getId(), "wrong customer");
    assertThat(unlinked.getIdentityStatus()).isEqualTo("UNLINKED");
    assertThat(unlinked.getCustomerAccountId()).isNull();

    var blocked = service.blockIdentity(identity.getId(), "spam");
    assertThat(blocked.getIdentityStatus()).isEqualTo("BLOCKED");
    assertThat(auditEventRepository.findAll()).extracting("action")
        .contains("CHANNEL_IDENTITY_LINKED", "CHANNEL_IDENTITY_UNLINKED", "CHANNEL_IDENTITY_BLOCKED");
    assertThat(changeRequestRepository.count()).isZero();
  }
}
