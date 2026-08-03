package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelConnectionRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sole allowed unscoped {@link ChannelConnection} lookup for public webhook intake.
 *
 * <p>Resolves one server-owned connection by opaque id, verifies provider type and {@code ACTIVE}
 * status, then returns the connection so the caller can establish tenant context from the connection
 * — never from a client {@code X-Tenant-Id} header.
 */
@Component
public class WebhookIntakeConnectionResolver {
  private final ChannelConnectionRepository connectionRepository;

  public WebhookIntakeConnectionResolver(ChannelConnectionRepository connectionRepository) {
    this.connectionRepository = connectionRepository;
  }

  /**
   * Resolve a connection for webhook intake. Failures use one stable authentication denial so the
   * response does not leak whether the connection, tenant, or secret exists.
   */
  public ChannelConnection resolveActiveConnection(UUID connectionId, ChannelProviderType expectedProvider) {
    if (connectionId == null || expectedProvider == null) {
      throw new WebhookAuthenticationException();
    }
    ChannelConnection connection =
        connectionRepository.findById(connectionId).orElseThrow(WebhookAuthenticationException::new);
    if (!expectedProvider.equals(connection.getProviderType())) {
      throw new WebhookAuthenticationException();
    }
    if (!"ACTIVE".equals(connection.getStatus())) {
      throw new WebhookAuthenticationException();
    }
    return connection;
  }
}
