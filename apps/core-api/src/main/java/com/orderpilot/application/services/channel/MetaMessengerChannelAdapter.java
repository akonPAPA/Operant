package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class MetaMessengerChannelAdapter extends Stage12ChannelAdapterSupport {
  public MetaMessengerChannelAdapter(ObjectMapper objectMapper) { super(ChannelProviderType.META_MESSENGER, ChannelType.API, objectMapper); }
}
