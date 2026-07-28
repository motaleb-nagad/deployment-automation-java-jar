-- Remove the fabricated demo seed rows (V2) so Promote, Registry and History show only real
-- console activity. Scoped to the exact seeded ids so any genuine promotion / deploy recorded
-- through the console is preserved. Portable DDL: runs on PostgreSQL 15 and H2.
--
-- audit_log is intentionally NOT touched here: under Postgres it is append-only (V10 trigger)
-- and the four seeded audit rows are not surfaced in any screen. Deploy/promotion/registry are.

-- deployment first — it carries a FK to promotion.
DELETE FROM deployment WHERE id IN ('DP-5011', 'DP-5008', 'DP-5004', 'DP-5003');

DELETE FROM promotion WHERE id IN ('PR-1042', 'PR-1041', 'PR-1040', 'PR-1039', 'PR-1037');

DELETE FROM jar_registry WHERE (app = 'apigw'      AND group_name = 'core')
                            OR (app = 'npsb_recon' AND group_name = 'core')
                            OR (app = 'spg'        AND group_name = 'core')
                            OR (app = 'auth'       AND group_name = 'web')
                            OR (app = 'ussdgwgp'   AND group_name = 'ussd');
