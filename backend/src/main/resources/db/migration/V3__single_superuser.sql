-- Provision the console with a single super user. Further accounts are created
-- manually by this super user via ADMIN. The multi-user demo seed, the approval
-- workflow and OTP are all retired.
--
-- password is bcrypt($2a$) — change it after first sign-in.

INSERT INTO app_user (username, name, email, role, scope, perm_r, perm_w, perm_x, password_hash) VALUES
 ('motaleb.bhuiyan@nagad.com.bd', 'Motaleb Bhuiyan', 'motaleb.bhuiyan@nagad.com.bd',
  'superadmin', 'all', TRUE, TRUE, TRUE,
  '$2a$10$ML2.CuezPv/G.tSkUKR7suLDEWrz0fsSKdjIDb5NWmQ18ojVJTio6');

-- Keep historical rows valid: promotion.requested_by is a FK to app_user, so re-point
-- any demo-user references at the super user before removing those accounts.
UPDATE promotion SET requested_by = 'motaleb.bhuiyan@nagad.com.bd'
 WHERE requested_by <> 'motaleb.bhuiyan@nagad.com.bd';
UPDATE promotion SET decided_by = 'motaleb.bhuiyan@nagad.com.bd'
 WHERE decided_by IS NOT NULL AND decided_by <> 'motaleb.bhuiyan@nagad.com.bd';

DELETE FROM app_user WHERE username <> 'motaleb.bhuiyan@nagad.com.bd';
