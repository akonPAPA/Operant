package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class WeChatWebhookVerifier extends AbstractProviderWebhookVerifier {
  @Override public ChannelProviderType providerType() { return ChannelProviderType.WECHAT; }
}
