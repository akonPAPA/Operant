-- OP-CAP-23 Event/Outbox-driven Order Journey Projector Runtime.
-- Additive, tenant-scoped internal event/projector runtime that moves the OP-CAP-22 Order Journey layer
-- from on-read materialization toward a durable, idempotent projection model. A business mutation (or an
-- explicit projection request) publishes a bounded, sanitized internal event; an explicit, tenant-scoped
-- projector runtime (NO background daemon) consumes it and refreshes the OrderJourney projection, recording
-- a per-(event, projector) checkpoint that guarantees an event is never double-projected. The OrderJourney
-- projection and its source objects remain the source of truth — the projector only DERIVES the journey and
-- never creates/approves orders, quotes, payments, or external/ERP/PSP/carrier writes. Every row is
-- tenant-isolated via tenant_id. All text columns are bounded VARCHAR (no unbounded TEXT). No raw documents,
-- OCR text, prompts, customer messages, secrets, card data, or bank credentials are stored — only sanitized,
-- bounded summaries. Dead-lettering is modelled with the event status DEAD_LETTERED plus the checkpoint
-- failure columns (no separate dead_letter table is required for this stage). V53 is not modified.

CREATE TABLE IF NOT EXISTS order_journey_projection_event (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          UUID NOT NULL REFERENCES tenant(id),
  event_type         VARCHAR(48) NOT NULL,
  source_type        VARCHAR(32) NOT NULL,
  source_id          UUID NULL,
  reason_code        VARCHAR(48) NULL,
  correlation_id     UUID NULL,
  causation_id       UUID NULL,
  idempotency_key    VARCHAR(160) NOT NULL,
  status             VARCHAR(16) NOT NULL,
  payload_version    INTEGER NOT NULL,
  payload_summary    VARCHAR(512) NULL,
  occurred_at        TIMESTAMPTZ NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL,
  processed_at       TIMESTAMPTZ NULL,
  failed_at          TIMESTAMPTZ NULL,
  failure_code       VARCHAR(48) NULL,
  failure_message    VARCHAR(512) NULL,
  retry_count        INTEGER NOT NULL,
  next_retry_at      TIMESTAMPTZ NULL,
  CONSTRAINT ux_order_journey_proj_event_tenant_idem UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT chk_order_journey_proj_event_retry CHECK (retry_count >= 0),
  CONSTRAINT chk_order_journey_proj_event_payload_version CHECK (payload_version >= 1)
);

CREATE INDEX IF NOT EXISTS idx_order_journey_proj_event_tenant_status_occurred
  ON order_journey_projection_event(tenant_id, status, occurred_at);

CREATE INDEX IF NOT EXISTS idx_order_journey_proj_event_tenant_type_occurred
  ON order_journey_projection_event(tenant_id, event_type, occurred_at);

CREATE INDEX IF NOT EXISTS idx_order_journey_proj_event_tenant_source
  ON order_journey_projection_event(tenant_id, source_type, source_id);

CREATE INDEX IF NOT EXISTS idx_order_journey_proj_event_tenant_next_retry
  ON order_journey_projection_event(tenant_id, next_retry_at);

CREATE TABLE IF NOT EXISTS order_journey_projection_checkpoint (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id               UUID NOT NULL REFERENCES tenant(id),
  projector_name          VARCHAR(80) NOT NULL,
  event_id                UUID NOT NULL REFERENCES order_journey_projection_event(id),
  event_type              VARCHAR(48) NOT NULL,
  source_type             VARCHAR(32) NOT NULL,
  source_id               UUID NULL,
  status                  VARCHAR(16) NOT NULL,
  projected_record_type   VARCHAR(48) NULL,
  projected_record_id     UUID NULL,
  idempotency_key         VARCHAR(160) NOT NULL,
  started_at              TIMESTAMPTZ NOT NULL,
  completed_at            TIMESTAMPTZ NULL,
  failed_at               TIMESTAMPTZ NULL,
  failure_code            VARCHAR(48) NULL,
  failure_message         VARCHAR(512) NULL,
  attempt_count           INTEGER NOT NULL,
  created_at              TIMESTAMPTZ NOT NULL,
  updated_at              TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_order_journey_checkpoint_tenant_projector_event UNIQUE (tenant_id, projector_name, event_id),
  CONSTRAINT ux_order_journey_checkpoint_tenant_projector_idem UNIQUE (tenant_id, projector_name, idempotency_key),
  CONSTRAINT chk_order_journey_checkpoint_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_order_journey_checkpoint_tenant_projector_status
  ON order_journey_projection_checkpoint(tenant_id, projector_name, status);

CREATE INDEX IF NOT EXISTS idx_order_journey_checkpoint_tenant_event_type_status
  ON order_journey_projection_checkpoint(tenant_id, event_type, status);
