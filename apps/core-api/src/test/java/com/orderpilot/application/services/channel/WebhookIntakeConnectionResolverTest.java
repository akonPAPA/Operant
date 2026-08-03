package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelConnectionRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookIntakeConnectionResolverTest {
  @Test
  void wrongProviderIsDeniedWithoutLeakingDetail() {
    ChannelConnectionRepository repository = mock(ChannelConnectionRepository.class);
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = mock(ChannelConnection.class);
    when(connection.getProviderType()).thenReturn(ChannelProviderType.TELEGRAM);
    when(connection.getStatus()).thenReturn("ACTIVE");
    when(repository.findById(connectionId)).thenReturn(Optional.of(connection));

    WebhookIntakeConnectionResolver resolver = new WebhookIntakeConnectionResolver(repository);

    assertThatThrownBy(() -> resolver.resolveActiveConnection(connectionId, ChannelProviderType.WHATSAPP))
        .isInstanceOf(WebhookAuthenticationException.class)
        .hasMessage(WebhookAuthenticationException.SAFE_MESSAGE);
  }

  @Test
  void inactiveConnectionIsDenied() {
    ChannelConnectionRepository repository = mock(ChannelConnectionRepository.class);
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = mock(ChannelConnection.class);
    when(connection.getProviderType()).thenReturn(ChannelProviderType.WHATSAPP);
    when(connection.getStatus()).thenReturn("PAUSED");
    when(repository.findById(connectionId)).thenReturn(Optional.of(connection));

    WebhookIntakeConnectionResolver resolver = new WebhookIntakeConnectionResolver(repository);

    assertThatThrownBy(() -> resolver.resolveActiveConnection(connectionId, ChannelProviderType.WHATSAPP))
        .isInstanceOf(WebhookAuthenticationException.class);
  }

  @Test
  void missingConnectionIsDeniedWithStableMessage() {
    ChannelConnectionRepository repository = mock(ChannelConnectionRepository.class);
    UUID connectionId = UUID.randomUUID();
    when(repository.findById(connectionId)).thenReturn(Optional.empty());

    WebhookIntakeConnectionResolver resolver = new WebhookIntakeConnectionResolver(repository);

    assertThatThrownBy(() -> resolver.resolveActiveConnection(connectionId, ChannelProviderType.TELEGRAM))
        .isInstanceOf(WebhookAuthenticationException.class)
        .hasMessage(WebhookAuthenticationException.SAFE_MESSAGE);
  }

  @Test
  void activeMatchingConnectionIsReturned() {
    ChannelConnectionRepository repository = mock(ChannelConnectionRepository.class);
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = mock(ChannelConnection.class);
    when(connection.getProviderType()).thenReturn(ChannelProviderType.TELEGRAM);
    when(connection.getStatus()).thenReturn("ACTIVE");
    when(repository.findById(connectionId)).thenReturn(Optional.of(connection));

    WebhookIntakeConnectionResolver resolver = new WebhookIntakeConnectionResolver(repository);

    assertThat(resolver.resolveActiveConnection(connectionId, ChannelProviderType.TELEGRAM))
        .isSameAs(connection);
  }
}
