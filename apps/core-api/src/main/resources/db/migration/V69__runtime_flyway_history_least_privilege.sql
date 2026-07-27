DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operant_runtime') THEN
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE flyway_schema_history FROM operant_runtime;
  END IF;
END;
$$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operant_migrator')
      AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operant_runtime') THEN
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE operant_migrator IN SCHEMA %I REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLES FROM operant_runtime',
        current_schema());
  END IF;
END;
$$;
