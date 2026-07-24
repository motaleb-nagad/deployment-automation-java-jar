-- PostgreSQL-only: enforce the audit_log as append-only at the database level.
-- Blocks UPDATE and DELETE even for the app role, so the compliance trail cannot
-- be rewritten by application code or an ad-hoc query. Runs only under the default
-- (Postgres) profile.

CREATE OR REPLACE FUNCTION audit_log_no_mutate() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_block_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_no_mutate();

CREATE TRIGGER audit_log_block_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_no_mutate();
