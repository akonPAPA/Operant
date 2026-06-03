DO $$
DECLARE
  table_record record;
BEGIN
  FOR table_record IN
    SELECT tablename
    FROM pg_tables
    WHERE schemaname = 'public'
      AND tablename <> 'flyway_schema_history'
  LOOP
    EXECUTE format('TRUNCATE TABLE public.%I CASCADE', table_record.tablename);
  END LOOP;
END $$;
