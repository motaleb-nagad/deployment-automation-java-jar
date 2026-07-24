-- Seed data mirrors the design prototype so the console has a realistic first run.
-- Demo password for every account is 'nagad1234' (bcrypt hash below). Replace with
-- SSO in production; never ship a shared password.

INSERT INTO app_user (username, name, email, role, scope, perm_r, perm_w, perm_x, password_hash) VALUES
 ('s.rahman', 'Shafiq Rahman', 's.rahman@nagad.com.bd', 'superadmin', 'all',                   TRUE, TRUE,  TRUE,  '$2a$10$Dow1Q0m8nO0zJ1qk4bK4/eYyG7oI0m2Yy0oV5hQ8eYX1mQ2p3Rk6'),
 ('t.ahmed',  'Tanvir Ahmed',  't.ahmed@nagad.com.bd',  'operator',   'core,web,apigwdoc',     TRUE, TRUE,  TRUE,  '$2a$10$Dow1Q0m8nO0zJ1qk4bK4/eYyG7oI0m2Yy0oV5hQ8eYX1mQ2p3Rk6'),
 ('m.hasan',  'Mahin Hasan',   'm.hasan@nagad.com.bd',  'operator',   'ussd,web-dmz,knotifypush', TRUE, TRUE, FALSE, '$2a$10$Dow1Q0m8nO0zJ1qk4bK4/eYyG7oI0m2Yy0oV5hQ8eYX1mQ2p3Rk6'),
 ('r.karim',  'Rumana Karim',  'r.karim@nagad.com.bd',  'viewer',     'all',                   TRUE, FALSE, FALSE, '$2a$10$Dow1Q0m8nO0zJ1qk4bK4/eYyG7oI0m2Yy0oV5hQ8eYX1mQ2p3Rk6');

INSERT INTO promotion (id, app, group_name, src_host, jar, git_hash, prev_hash, branch, size_label, requested_by, requested_at, status, decided_by, decided_at, note) VALUES
 ('PR-1042', 'spg',        'core', 'ngd-dc-core-01',   'secure-payment-gateway-1.0.jar',   'd155cc3', 'a41c7d2', 'release/8.2', '48.2 MB', 't.ahmed',  TIMESTAMP '2026-07-24 09:12:00', 'pending',  NULL,       NULL,                             NULL),
 ('PR-1041', 'apigw',      'core', 'ngd-dc-core-01',   'api-gateway-1.0.jar',              '7e51e9f', '3ba90c2', 'release/8.2', '51.7 MB', 't.ahmed',  TIMESTAMP '2026-07-24 08:40:00', 'approved', 's.rahman', TIMESTAMP '2026-07-24 08:55:00', NULL),
 ('PR-1040', 'ussdgwgp',   'ussd', 'ngd-dc-core-01',   'ussd-api-gateway-1.0.jar',         'b90e134', '2c7d901', 'release/4.0', '19.8 MB', 'm.hasan',  TIMESTAMP '2026-07-24 07:05:00', 'approved', 's.rahman', TIMESTAMP '2026-07-24 07:20:00', NULL),
 ('PR-1039', 'npsb_recon', 'core', 'ngd-dc-core-01',   'npsb-reconciliation-1.0.0.jar',    'a41c7d2', '88f4c21', 'main',        '22.1 MB', 's.rahman', TIMESTAMP '2026-07-23 13:50:00', 'deployed', 's.rahman', TIMESTAMP '2026-07-23 14:02:00', NULL),
 ('PR-1037', 'auth',       'web',  'ngd-dc-portal-01', 'authentication-server-1.0.jar',    'f440ab1', '9c02d17', 'release/8.1', '33.4 MB', 'm.hasan',  TIMESTAMP '2026-07-22 17:20:00', 'denied',   's.rahman', TIMESTAMP '2026-07-22 17:45:00', 'Build not signed off by QA — resubmit after the regression pass.');

INSERT INTO jar_registry (app, group_name, jar, prod_hash, updated_at, updated_by) VALUES
 ('apigw',      'core', 'api-gateway-1.0.jar',           '3ba90c2', TIMESTAMP '2026-07-22 22:58:00', 'm.hasan'),
 ('npsb_recon', 'core', 'npsb-reconciliation-1.0.0.jar', 'a41c7d2', TIMESTAMP '2026-07-23 14:02:00', 's.rahman'),
 ('spg',        'core', 'secure-payment-gateway-1.0.jar','a41c7d2', TIMESTAMP '2026-07-20 10:00:00', 't.ahmed'),
 ('auth',       'web',  'authentication-server-1.0.jar', 'f440ab1', TIMESTAMP '2026-06-18 21:02:00', 's.rahman'),
 ('ussdgwgp',   'ussd', 'ussd-api-gateway-1.0.jar',      '2c7d901', TIMESTAMP '2026-07-11 01:05:00', 'm.hasan');

INSERT INTO deployment (id, promotion_id, group_name, hosts, apps, actions, started_by, started_at, duration, result, before_after, log_excerpt) VALUES
 ('DP-5011', 'PR-1039', 'core', 'app1..app6', 'npsb_recon', 'stop,deploy,start', 's.rahman', TIMESTAMP '2026-07-23 14:11:00', '4m 12s', 'ok',      '{"app1":["88f4c21","a41c7d2"],"app6":["88f4c21","a41c7d2"]}', 'PLAY RECAP — app1..app6: ok=4 changed=3 failed=0'),
 ('DP-5008', NULL,      'apigwdoc','all',      'apigw',      'stop,deploy',       't.ahmed',  TIMESTAMP '2026-07-23 11:47:00', '2m 03s', 'ok',      '{"apigwdoc1":["51c0aa9","9d21e04"]}', 'PLAY RECAP — 3 hosts: ok=3 changed=2 failed=0'),
 ('DP-5004', NULL,      'core', 'app5..app6', 'apigw',      'stop,deploy,start', 'm.hasan',  TIMESTAMP '2026-07-22 23:30:00', '1m 40s', 'failed',  '{"app5":["3ba90c2","3ba90c2"],"app6":["-","-"]}', 'fatal: [nagad-app6]: UNREACHABLE! => ssh timeout after 30s'),
 ('DP-5003', NULL,      'core', 'app1..app4', 'apigw',      'stop,deploy,start', 'm.hasan',  TIMESTAMP '2026-07-22 22:58:00', '5m 51s', 'ok',      '{"app1":["3ba90c2","7e51e9f"]}', 'PLAY RECAP — app1..app4: ok=4 changed=3 failed=0');

INSERT INTO audit_log (ts, actor, verb, target, detail) VALUES
 (TIMESTAMP '2026-07-24 09:12:00', 't.ahmed',  'fetch',   'PR-1042 spg',   'fetched secure-payment-gateway-1.0.jar (d155cc3) from ngd-dc-core-01'),
 (TIMESTAMP '2026-07-24 08:55:00', 's.rahman', 'approve', 'PR-1041 apigw', 'approved api-gateway-1.0.jar (7e51e9f) for core'),
 (TIMESTAMP '2026-07-23 14:11:00', 's.rahman', 'deploy',  'core apigw',    'stop,deploy,start app1..app6 → a41c7d2'),
 (TIMESTAMP '2026-07-22 17:45:00', 's.rahman', 'deny',    'PR-1037 auth',  'Build not signed off by QA');
