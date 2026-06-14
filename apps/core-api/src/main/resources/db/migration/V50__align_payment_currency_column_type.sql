-- OP-CAP-17C follow-up — live PostgreSQL schema-validation alignment (no business-logic change).
--
-- The OP-CAP-17C entities PaymentObligation and PaymentAllocation map `currency` with a plain
-- @Column(length = 3), which Hibernate validates as VARCHAR(3). The original V46 migration created the
-- column as CHAR(3) (bpchar), copied from the older V2/V4 currency columns. Those older columns are
-- intentionally CHAR(3) and their entities declare columnDefinition = "char(3)", so they validate fine.
-- The two payment_* columns do not, which fails Hibernate schema validation on real PostgreSQL with:
--   wrong column type encountered in column [currency] in table [payment_allocation];
--   found [bpchar (Types#CHAR)], but expecting [varchar(3) (Types#VARCHAR)].
--
-- V46 is already applied, so it must not be edited (Flyway checksum + applied-migration rule). This
-- forward migration alters the two columns to VARCHAR(3) to match the entity mapping. 3-character ISO
-- currency codes are never blank-padded, so the bpchar -> varchar conversion is value-preserving.

ALTER TABLE payment_obligation ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE payment_allocation ALTER COLUMN currency TYPE VARCHAR(3);
