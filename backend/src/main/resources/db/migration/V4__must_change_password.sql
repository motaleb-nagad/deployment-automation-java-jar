-- Force a password change on first login.
--
-- Accounts are provisioned by the super admin with an initial, admin-chosen password
-- that is shared with the user out of band. That password is single-use: the account
-- starts flagged, and the user must set a password of their own before the console will
-- let them do anything else. After that change no one else — not even the super admin —
-- knows the account's password. Portable DDL: runs on PostgreSQL 15 and on H2.

ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- The seeded super admin still holds a well-known bootstrap password (see V3); require
-- the same one-time change on first sign-in.
UPDATE app_user SET must_change_password = TRUE
 WHERE username = 'motaleb.bhuiyan@nagad.com.bd';
