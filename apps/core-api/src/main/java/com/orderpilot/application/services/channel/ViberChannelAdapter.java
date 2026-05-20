package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelProviderType;
import org.springframework.stereotype.Component;

@Component
public class ViberChannelAdapter extends Stage12ChannelAdapterSupport {
  public ViberChannelAdapter(ObjectMapper objectMapper) { super(ChannelProviderType.VIBER, ChannelType.API, objectMapper); }
}
