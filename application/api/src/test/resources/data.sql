INSERT INTO permissions (permission_id, permission_code, permission_name, created_by, updated_by)
VALUES (1, 'USER_READ', 'Read users', 0, 0), (2, 'USER_WRITE', 'Write users', 0, 0);

INSERT INTO roles (role_id, role_code, role_name, created_by, updated_by)
VALUES (1, 'ADMIN', 'Administrator', 0, 0), (2, 'USER', 'Standard user', 0, 0);

INSERT INTO role_permissions (role_id, permission_id) VALUES (1, 1), (1, 2), (2, 1);

INSERT INTO users (user_id, username, email, password, created_by, updated_by)
VALUES (1, 'admin', 'admin@example.com', '$2a$10$eHdnG7uGKUnlpg/2H.zSv.qhmC.Bf0vRpGRyEmF4CTP3wE1vkSdaC', 0, 0),
       (2, 'normal', 'normal@example.com', '$2a$10$p6OTUNjOeNlBq3.Fx2G9V.OyWQAYTIzqbaoLFTl3rFvUNnqtOAKqi', 0, 0);

INSERT INTO user_roles (user_id, role_id) VALUES (1, 1), (2, 2);

-- Explicit user ids above do not advance the IDENTITY sequence (Postgres/H2 semantics);
-- restart it past the seed so API-created users don't collide on the primary key.
ALTER TABLE users ALTER COLUMN user_id RESTART WITH 100;
