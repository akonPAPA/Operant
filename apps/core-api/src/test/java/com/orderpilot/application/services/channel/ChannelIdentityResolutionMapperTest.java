package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage10DOmnichannelDtos.ChannelIdentityResolutionView;
import com.orderpilot.domain.channel.ChannelIdentity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-06D unit tests for the stable identity read contract. No Spring context required — pure
 * mapping logic. Each test corresponds to one frontend resolution status.
 */
class ChannelIdentityResolutionMapperTest {
  private static final UUID TENANT = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-06-05T12:00:00Z");

  @Test
  void linkedIdentityReturnsResolvedStatus() {
    UUID accountId = UUID.randomUUID();
    UUID contactId = UUID.randomUUID();
    ChannelIdentity identity = unlinked("chat-linked");
    identity.link(accountId, contactId, UUID.randomUUID(), null, NOW);

    ChannelIdentityResolutionView view = ChannelIdentityResolutionMapper.toResolutionView(identity);

    assertThat(view.status()).isEqualTo("RESOLVED");
    assertThat(view.customerAccountId()).isEqualTo(accountId);
    assertThat(view.customerContactId()).isEqualTo(contactId);
    assertThat(view.externalSenderId()).isEqualTo("chat-linked");
    assertThat(view.reason()).isEqualTo("LINKED_CUSTOMER_CONTACT");
  }

  @Test
  void blockedIdentityReturnsBlockedStatusWithoutCustomerIds() {
    ChannelIdentity identity = unlinked("chat-blocked");
    identity.block(null, NOW);

    ChannelIdentityResolutionView view = ChannelIdentityResolutionMapper.toResolutionView(identity);

    assertThat(view.status()).isEqualTo("BLOCKED");
    assertThat(view.customerAccountId()).isNull();
    assertThat(view.customerContactId()).isNull();
    assertThat(view.reason()).isEqualTo("BLOCKED_IDENTITY");
  }

  @Test
  void suggestedMatchReturnsAmbiguousStatus() {
    ChannelIdentity identity = unlinked("chat-suggested");
    identity.suggestMatch(UUID.randomUUID(), UUID.randomUUID(), null, null, NOW);

    ChannelIdentityResolutionView view = ChannelIdentityResolutionMapper.toResolutionView(identity);

    assertThat(view.status()).isEqualTo("AMBIGUOUS");
    assertThat(view.reason()).isEqualTo("SUGGESTED_MATCH");
  }

  @Test
  void needsReviewReturnsAmbiguousStatus() {
    ChannelIdentity identity = unlinked("chat-nr");
    identity.needsReview(null, NOW);

    ChannelIdentityResolutionView view = ChannelIdentityResolutionMapper.toResolutionView(identity);

    assertThat(view.status()).isEqualTo("AMBIGUOUS");
    assertThat(view.reason()).isEqualTo("NEEDS_REVIEW");
  }

  @Test
  void unlinkedIdentityReturnsUnknownStatus() {
    ChannelIdentity identity = unlinked("chat-unlinked");

    ChannelIdentityResolutionView view = ChannelIdentityResolutionMapper.toResolutionView(identity);

    assertThat(view.status()).isEqualTo("UNKNOWN");
    assertThat(view.reason()).isEqualTo("UNLINKED");
    assertThat(view.customerAccountId()).isNull();
    assertThat(view.customerContactId()).isNull();
  }

  @Test
  void blankExternalSenderIdReturnsNotApplicable() {
    ChannelIdentity identity = new ChannelIdentity(TENANT, "TELEGRAM", "", null, null, null, NOW);

    ChannelIdentityResolutionView view = ChannelIdentityResolutionMapper.toResolutionView(identity);

    assertThat(view.status()).isEqualTo("NOT_APPLICABLE");
    assertThat(view.reason()).isEqualTo("NO_EXTERNAL_SENDER_ID");
    assertThat(view.customerAccountId()).isNull();
  }

  @Test
  void resolvedViewContainsChannelIdentityIdForFrontendReference() {
    ChannelIdentity identity = unlinked("chat-id-ref");
    identity.link(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, NOW);

    ChannelIdentityResolutionView view = ChannelIdentityResolutionMapper.toResolutionView(identity);

    // channelIdentityId may be null when entity is unsaved (no generated ID yet),
    // but the field must exist in the response for frontend to reference.
    assertThat(view.externalSenderId()).isEqualTo("chat-id-ref");
    assertThat(view.updatedAt()).isNotNull();
  }

  private static ChannelIdentity unlinked(String senderId) {
    return new ChannelIdentity(TENANT, "TELEGRAM", senderId, null, null, null, NOW);
  }
}
