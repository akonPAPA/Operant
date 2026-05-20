CREATE INDEX IF NOT EXISTS idx_product_substitute_tenant_source_active
  ON product_substitute(tenant_id, source_product_id, active);

CREATE INDEX IF NOT EXISTS idx_product_substitute_tenant_substitute_active
  ON product_substitute(tenant_id, substitute_product_id, active);

CREATE INDEX IF NOT EXISTS idx_product_compatibility_tenant_product_active
  ON product_compatibility(tenant_id, product_id, active);

CREATE INDEX IF NOT EXISTS idx_product_compatibility_tenant_vehicle
  ON product_compatibility(tenant_id, make, model, year_from, year_to, active);

CREATE INDEX IF NOT EXISTS idx_customer_substitution_preference_tenant_customer
  ON customer_substitution_preference(tenant_id, customer_account_id);

CREATE INDEX IF NOT EXISTS idx_customer_substitution_preference_tenant_blocked
  ON customer_substitution_preference(tenant_id, customer_account_id, product_id, blocked_substitute_product_id);
