package com.orderpilot.domain.bot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotResponseDraftRepository extends JpaRepository<BotResponseDraft, UUID> {
  Optional<BotResponseDraft> findByIdAndTenantId(UUID id, UUID tenantId);
  List<BotResponseDraft> findByTenantIdAndConversationIdOrderByCreatedAtDesc(UUID tenantId, UUID conversationId);
}
