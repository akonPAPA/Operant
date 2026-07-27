\set ON_ERROR_STOP on

SELECT format(
  'CREATE ROLE operant_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %L',
  :'migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operant_migrator')
\gexec

SELECT format(
  'CREATE ROLE operant_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %L',
  :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operant_runtime')
\gexec

ALTER ROLE operant_migrator WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'migrator_password';
ALTER ROLE operant_runtime WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'runtime_password';

REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO operant_migrator;
GRANT CONNECT ON DATABASE :"db_name" TO operant_runtime;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO operant_migrator;
GRANT USAGE ON SCHEMA public TO operant_runtime;

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO operant_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO operant_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE operant_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE ON TABLES TO operant_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE operant_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO operant_runtime;

DO $$
BEGIN
  IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON flyway_schema_history FROM operant_runtime;
    ALTER DEFAULT PRIVILEGES FOR ROLE operant_migrator IN SCHEMA public
      REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLES FROM operant_runtime;
  END IF;
  IF to_regclass('public.lifecycle_operation_audit') IS NOT NULL THEN
    REVOKE UPDATE, DELETE, TRUNCATE ON lifecycle_operation_audit FROM operant_runtime;
  END IF;
  IF to_regclass('public.backup_artifact') IS NOT NULL THEN
    REVOKE DELETE, TRUNCATE ON backup_artifact FROM operant_runtime;
  END IF;
  IF to_regclass('public.lifecycle_operation') IS NOT NULL THEN
    REVOKE DELETE, TRUNCATE ON lifecycle_operation FROM operant_runtime;
  END IF;
END;
$$;
