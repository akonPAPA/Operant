package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class EmailChannelAdapter extends Stage12ChannelAdapterSupport {
  public EmailChannelAdapter(ObjectMapper objectMapper) { super(ChannelProviderType.EMAIL, ChannelType.EMAIL, objectMapper); }
}
