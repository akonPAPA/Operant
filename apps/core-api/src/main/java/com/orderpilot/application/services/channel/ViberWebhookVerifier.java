package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ViberWebhookVerifier extends AbstractProviderWebhookVerifier {
  public ViberWebhookVerifier() {
    this(WebhookVerificationAuthority.forTests(false, false));
  }

  @Autowired
  public ViberWebhookVerifier(WebhookVerificationAuthority authority) {
    super(authority);
  }

  @Override
  public ChannelProviderType providerType() {
    return ChannelProviderType.VIBER;
  }
}
