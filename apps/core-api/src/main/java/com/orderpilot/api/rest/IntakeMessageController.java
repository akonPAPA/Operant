package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage3Dtos.*;
import com.orderpilot.application.services.ChannelMessageService;
import com.orderpilot.domain.intake.ChannelMessage;
import java.util.*; import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/intake")
public class IntakeMessageController {
  private final ChannelMessageService service; public IntakeMessageController(ChannelMessageService service){this.service=service;}
  @PostMapping("/messages") public ChannelMessageResponse create(@RequestBody MessageRequest request){ return toResponse(service.create(request)); }
  @GetMapping("/messages") public List<ChannelMessageResponse> list(){ return service.list().stream().map(this::toResponse).toList(); }
  @GetMapping("/messages/{id}") public ChannelMessageResponse get(@PathVariable UUID id){ return toResponse(service.get(id)); }
  @GetMapping("/conversations/{conversationId}") public List<ChannelMessageResponse> conversation(@PathVariable String conversationId){ return service.conversation(conversationId).stream().map(this::toResponse).toList(); }
  private ChannelMessageResponse toResponse(ChannelMessage m){ return new ChannelMessageResponse(m.getId(), m.getChannel(), m.getExternalMessageId(), m.getConversationId(), m.getSenderHandle(), m.getMessageType(), m.getTextContent(), m.getStatus(), m.getReceivedAt()); }
}