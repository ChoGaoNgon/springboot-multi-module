-- // add_google_sub_to_users
-- Migration SQL that makes the change goes here.

ALTER TABLE users ADD COLUMN google_sub VARCHAR(255);
CREATE UNIQUE INDEX users_google_sub_uk ON users (google_sub) WHERE google_sub IS NOT NULL;

-- //@UNDO
-- SQL to undo the change goes here.

DROP INDEX IF EXISTS users_google_sub_uk;
ALTER TABLE users DROP COLUMN google_sub;
