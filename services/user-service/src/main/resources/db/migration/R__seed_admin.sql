-- Dev seed: one ADMIN user. The admin has no wallet (no user.registered event).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users(id, email, password_hash, display_name, roles)
VALUES ('00000000-0000-0000-0000-000000000001', lower('${adminEmail}'), crypt('${adminPassword}', gen_salt('bf', 12)), 'Admin', 'USER,ADMIN')
ON CONFLICT (email) DO NOTHING;
