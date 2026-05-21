package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class TelegramWebhookVerifier extends AbstractProviderWebhookVerifier {
  @Override public ChannelProviderType providerType() { return ChannelProviderType.TELEGRAM; }
}
