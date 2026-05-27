ALTER TABLE bot_conversation
  ADD COLUMN IF NOT EXISTS linked_review_case_id UUID NULL REFERENCES exception_case(id),
  ADD COLUMN IF NOT EXISTS policy_decision VARCHAR(80) NULL,
  ADD COLUMN IF NOT EXISTS suggested_next_action VARCHAR(160) NULL;

CREATE INDEX IF NOT EXISTS idx_bot_conversation_tenant_review_case ON bot_conversation(tenant_id, linked_review_case_id);
