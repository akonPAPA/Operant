package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WhatsAppInboundAdapterTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(CoreConfiguration.class, WhatsAppInboundAdapter.class);

  @Test
  void normalizesWhatsAppBusinessTextPayload() {
    contextRunner.run(context -> {
      WhatsAppInboundAdapter adapter = context.getBean(WhatsAppInboundAdapter.class);
      var messages = adapter.normalize(objectMapper.readTree("""
          {"object":"whatsapp_business_account","entry":[{"id":"waba-1","changes":[{"value":{"contacts":[{"profile":{"name":"Buyer One"},"wa_id":"77001112233"}],"messages":[{"from":"77001112233","id":"wamid.abc","timestamp":"1710000000","text":{"body":"Need filters"},"type":"text"}]}}]}]}
          """));

      assertThat(messages).hasSize(1);
      assertThat(messages.get(0).channelType()).isEqualTo(ChannelType.WHATSAPP);
      assertThat(messages.get(0).externalMessageId()).isEqualTo("wamid.abc");
      assertThat(messages.get(0).senderPhone()).isEqualTo("77001112233");
      assertThat(messages.get(0).rawText()).isEqualTo("Need filters");
    });
  }

  @Test
  void ignoresUnsupportedNonTextMessagesSafely() {
    contextRunner.run(context -> {
      WhatsAppInboundAdapter adapter = context.getBean(WhatsAppInboundAdapter.class);
      var messages = adapter.normalize(objectMapper.readTree("""
          {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"messages":[{"from":"77001112233","id":"wamid.image","type":"image"}]}}]}]}
          """));

      assertThat(messages).isEmpty();
    });
  }
}