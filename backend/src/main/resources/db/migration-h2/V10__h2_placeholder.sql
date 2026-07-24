-- H2 (dev profile) placeholder. The Postgres build enforces audit_log append-only
-- with a trigger (see db/migration-pg/V10). H2 dev runs skip that DB-level guard;
-- the AuditService never issues UPDATE/DELETE against audit_log regardless.
SELECT 1;
