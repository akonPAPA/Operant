CREATE TABLE exception_case (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  case_number VARCHAR(80) NOT NULL,
  source_type VARCHAR(40) NOT NULL,
  source_id UUID NULL,
  extraction_result_id UUID NULL REFERENCES extraction_result(id),
  validation_run_id UUID NULL REFERENCES validation_run(id),
  customer_account_id UUID NULL REFERENCES customer_account(id),
  title VARCHAR(255) NOT NULL,
  status VARCHAR(40) NOT NULL,
  priority VARCHAR(20) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  assigned_to_user_id UUID NULL REFERENCES user_account(id),
  summary TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ NULL,
  CONSTRAINT uq_exception_case_tenant_number UNIQUE (tenant_id, case_number)
);

CREATE TABLE exception_case_issue (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  exception_case_id UUID NOT NULL REFERENCES exception_case(id),
  validation_issue_id UUID NULL REFERENCES validation_issue(id),
  issue_type VARCHAR(80) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  status VARCHAR(30) NOT NULL,
  message TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE suggested_fix (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  exception_case_id UUID NULL REFERENCES exception_case(id),
  validation_run_id UUID NULL REFERENCES validation_run(id),
  validation_issue_id UUID NULL REFERENCES validation_issue(id),
  extracted_line_item_id UUID NULL REFERENCES extracted_line_item(id),
  fix_type VARCHAR(40) NOT NULL,
  status VARCHAR(30) NOT NULL,
  suggestion_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  confidence NUMERIC(8,4) NULL,
  reason TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE draft_quote (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  quote_number VARCHAR(80) NOT NULL,
  customer_account_id UUID NULL REFERENCES customer_account(id),
  source_extraction_result_id UUID NULL REFERENCES extraction_result(id),
  source_validation_run_id UUID NULL REFERENCES validation_run(id),
  source_exception_case_id UUID NULL REFERENCES exception_case(id),
  status VARCHAR(40) NOT NULL,
  currency VARCHAR(12) NULL,
  subtotal_amount NUMERIC(18,4) NULL,
  discount_amount NUMERIC(18,4) NULL,
  total_amount NUMERIC(18,4) NULL,
  margin_percent NUMERIC(8,4) NULL,
  notes TEXT NULL,
  created_by UUID NULL REFERENCES user_account(id),
  approved_by UUID NULL REFERENCES user_account(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  approved_at TIMESTAMPTZ NULL,
  CONSTRAINT uq_draft_quote_tenant_number UNIQUE (tenant_id, quote_number)
);

CREATE TABLE draft_quote_line (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  draft_quote_id UUID NOT NULL REFERENCES draft_quote(id),
  source_extracted_line_item_id UUID NULL REFERENCES extracted_line_item(id),
  product_id UUID NULL REFERENCES product(id),
  selected_substitute_product_id UUID NULL REFERENCES product(id),
  line_number INTEGER NOT NULL,
  description TEXT NULL,
  quantity NUMERIC(18,4) NOT NULL,
  uom VARCHAR(40) NOT NULL,
  unit_price NUMERIC(18,4) NULL,
  discount_percent NUMERIC(8,4) NULL,
  line_total NUMERIC(18,4) NULL,
  margin_percent NUMERIC(8,4) NULL,
  status VARCHAR(30) NOT NULL,
  validation_status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE draft_order (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  order_number VARCHAR(80) NOT NULL,
  customer_account_id UUID NULL REFERENCES customer_account(id),
  source_extraction_result_id UUID NULL REFERENCES extraction_result(id),
  source_validation_run_id UUID NULL REFERENCES validation_run(id),
  source_exception_case_id UUID NULL REFERENCES exception_case(id),
  status VARCHAR(40) NOT NULL,
  currency VARCHAR(12) NULL,
  subtotal_amount NUMERIC(18,4) NULL,
  discount_amount NUMERIC(18,4) NULL,
  total_amount NUMERIC(18,4) NULL,
  margin_percent NUMERIC(8,4) NULL,
  requested_date DATE NULL,
  ship_to_location_id UUID NULL REFERENCES location(id),
  notes TEXT NULL,
  created_by UUID NULL REFERENCES user_account(id),
  approved_by UUID NULL REFERENCES user_account(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  approved_at TIMESTAMPTZ NULL,
  CONSTRAINT uq_draft_order_tenant_number UNIQUE (tenant_id, order_number)
);

CREATE TABLE draft_order_line (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  draft_order_id UUID NOT NULL REFERENCES draft_order(id),
  source_extracted_line_item_id UUID NULL REFERENCES extracted_line_item(id),
  product_id UUID NULL REFERENCES product(id),
  selected_substitute_product_id UUID NULL REFERENCES product(id),
  line_number INTEGER NOT NULL,
  description TEXT NULL,
  quantity NUMERIC(18,4) NOT NULL,
  uom VARCHAR(40) NOT NULL,
  unit_price NUMERIC(18,4) NULL,
  discount_percent NUMERIC(8,4) NULL,
  line_total NUMERIC(18,4) NULL,
  margin_percent NUMERIC(8,4) NULL,
  requested_date DATE NULL,
  status VARCHAR(30) NOT NULL,
  validation_status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE approval_decision (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  target_type VARCHAR(40) NOT NULL,
  target_id UUID NOT NULL,
  decision VARCHAR(30) NOT NULL,
  reason TEXT NULL,
  decided_by UUID NULL REFERENCES user_account(id),
  decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE operator_action (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  actor_user_id UUID NULL REFERENCES user_account(id),
  target_type VARCHAR(40) NOT NULL,
  target_id UUID NOT NULL,
  action_type VARCHAR(60) NOT NULL,
  message TEXT NULL,
  metadata_json JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_note (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  target_type VARCHAR(40) NOT NULL,
  target_id UUID NOT NULL,
  note_text TEXT NOT NULL,
  created_by UUID NULL REFERENCES user_account(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_exception_case_tenant_status_created ON exception_case(tenant_id, status, created_at DESC);
CREATE INDEX idx_exception_case_tenant_priority_status ON exception_case(tenant_id, priority, status);
CREATE INDEX idx_exception_case_tenant_validation ON exception_case(tenant_id, validation_run_id);
CREATE INDEX idx_exception_case_tenant_customer ON exception_case(tenant_id, customer_account_id);
CREATE INDEX idx_exception_case_tenant_assignee ON exception_case(tenant_id, assigned_to_user_id);
CREATE INDEX idx_exception_case_issue_tenant_case ON exception_case_issue(tenant_id, exception_case_id);
CREATE INDEX idx_exception_case_issue_tenant_validation_issue ON exception_case_issue(tenant_id, validation_issue_id);
CREATE INDEX idx_exception_case_issue_tenant_status ON exception_case_issue(tenant_id, status);
CREATE INDEX idx_suggested_fix_tenant_case ON suggested_fix(tenant_id, exception_case_id);
CREATE INDEX idx_suggested_fix_tenant_validation ON suggested_fix(tenant_id, validation_run_id);
CREATE INDEX idx_suggested_fix_tenant_issue ON suggested_fix(tenant_id, validation_issue_id);
CREATE INDEX idx_suggested_fix_tenant_status ON suggested_fix(tenant_id, status);
CREATE INDEX idx_draft_quote_tenant_status_created ON draft_quote(tenant_id, status, created_at DESC);
CREATE INDEX idx_draft_quote_tenant_customer ON draft_quote(tenant_id, customer_account_id);
CREATE INDEX idx_draft_quote_tenant_validation ON draft_quote(tenant_id, source_validation_run_id);
CREATE INDEX idx_draft_quote_tenant_case ON draft_quote(tenant_id, source_exception_case_id);
CREATE INDEX idx_draft_quote_line_tenant_quote ON draft_quote_line(tenant_id, draft_quote_id);
CREATE INDEX idx_draft_quote_line_tenant_product ON draft_quote_line(tenant_id, product_id);
CREATE INDEX idx_draft_quote_line_tenant_status ON draft_quote_line(tenant_id, status);
CREATE INDEX idx_draft_order_tenant_status_created ON draft_order(tenant_id, status, created_at DESC);
CREATE INDEX idx_draft_order_tenant_customer ON draft_order(tenant_id, customer_account_id);
CREATE INDEX idx_draft_order_tenant_validation ON draft_order(tenant_id, source_validation_run_id);
CREATE INDEX idx_draft_order_tenant_case ON draft_order(tenant_id, source_exception_case_id);
CREATE INDEX idx_draft_order_line_tenant_order ON draft_order_line(tenant_id, draft_order_id);
CREATE INDEX idx_draft_order_line_tenant_product ON draft_order_line(tenant_id, product_id);
CREATE INDEX idx_draft_order_line_tenant_status ON draft_order_line(tenant_id, status);
CREATE INDEX idx_approval_decision_tenant_target ON approval_decision(tenant_id, target_type, target_id);
CREATE INDEX idx_approval_decision_tenant_decision_created ON approval_decision(tenant_id, decision, created_at DESC);
CREATE INDEX idx_operator_action_tenant_target ON operator_action(tenant_id, target_type, target_id);
CREATE INDEX idx_operator_action_tenant_actor ON operator_action(tenant_id, actor_user_id);
CREATE INDEX idx_operator_action_tenant_created ON operator_action(tenant_id, created_at DESC);
CREATE INDEX idx_workspace_note_tenant_target ON workspace_note(tenant_id, target_type, target_id);
CREATE INDEX idx_workspace_note_tenant_created ON workspace_note(tenant_id, created_at DESC);
