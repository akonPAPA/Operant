package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class MetaMessengerWebhookVerifier extends AbstractProviderWebhookVerifier {
  @Override public ChannelProviderType providerType() { return ChannelProviderType.META_MESSENGER; }
}
