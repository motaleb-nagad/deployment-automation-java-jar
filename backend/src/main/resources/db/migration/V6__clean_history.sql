-- Clean the History screen. Old test/demo runs were recorded under accounts that no longer
-- exist (the multi-user demo seed was retired in V3), including some left stuck in the
-- 'running' state because their stream never finalized. Remove every deployment whose actor
-- is not a current account, so History shows only real runs by live users.
-- Portable DDL: runs on PostgreSQL 15 and H2.

DELETE FROM deployment
 WHERE started_by NOT IN (SELECT username FROM app_user);
