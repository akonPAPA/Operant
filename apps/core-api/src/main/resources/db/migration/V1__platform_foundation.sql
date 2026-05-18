CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenant (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug VARCHAR(120) NOT NULL UNIQUE,
  legal_name VARCHAR(255) NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_account (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  email VARCHAR(320) NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_user_account_tenant_email UNIQUE (tenant_id, email)
);

CREATE TABLE role (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  code VARCHAR(120) NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_role_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE permission (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(160) NOT NULL UNIQUE,
  description TEXT NOT NULL
);

CREATE TABLE user_role (
  user_id UUID NOT NULL REFERENCES user_account(id),
  role_id UUID NOT NULL REFERENCES role(id),
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permission (
  role_id UUID NOT NULL REFERENCES role(id),
  permission_id UUID NOT NULL REFERENCES permission(id),
  granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE audit_event (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  actor_id UUID NULL REFERENCES user_account(id),
  action VARCHAR(160) NOT NULL,
  entity_type VARCHAR(160) NOT NULL,
  entity_id VARCHAR(160) NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_key (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  key_hash VARCHAR(128) NOT NULL,
  request_fingerprint VARCHAR(128) NOT NULL,
  response_status INTEGER NULL,
  response_body JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_idempotency_tenant_key UNIQUE (tenant_id, key_hash)
);

CREATE INDEX idx_user_account_tenant ON user_account(tenant_id);
CREATE INDEX idx_role_tenant ON role(tenant_id);
CREATE INDEX idx_audit_event_tenant_entity ON audit_event(tenant_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_event_tenant_action ON audit_event(tenant_id, action, occurred_at DESC);
CREATE INDEX idx_idempotency_key_expiry ON idempotency_key(expires_at);