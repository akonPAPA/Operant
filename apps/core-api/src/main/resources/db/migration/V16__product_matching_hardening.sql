ALTER TABLE product
  ADD COLUMN IF NOT EXISTS normalized_sku VARCHAR(160);

UPDATE product
SET normalized_sku = regexp_replace(upper(trim(sku)), '[-_ /]+', '', 'g')
WHERE normalized_sku IS NULL OR normalized_sku = '';

UPDATE product_alias
SET normalized_alias = regexp_replace(upper(trim(raw_alias)), '[-_ /]+', '', 'g')
WHERE raw_alias IS NOT NULL;

UPDATE oem_reference
SET normalized_oem_code = regexp_replace(upper(trim(oem_code)), '[-_ /]+', '', 'g')
WHERE oem_code IS NOT NULL;

ALTER TABLE product
  ALTER COLUMN normalized_sku SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_product_tenant_normalized_sku
  ON product(tenant_id, normalized_sku);

CREATE INDEX IF NOT EXISTS idx_product_alias_tenant_customer_normalized
  ON product_alias(tenant_id, customer_account_id, normalized_alias);
