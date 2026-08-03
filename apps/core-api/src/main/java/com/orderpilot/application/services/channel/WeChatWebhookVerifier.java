package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WeChatWebhookVerifier extends AbstractProviderWebhookVerifier {
  public WeChatWebhookVerifier() {
    this(WebhookVerificationAuthority.forTests(false, false));
  }

  @Autowired
  public WeChatWebhookVerifier(WebhookVerificationAuthority authority) {
    super(authority);
  }

  @Override
  public ChannelProviderType providerType() {
    return ChannelProviderType.WECHAT;
  }
}
