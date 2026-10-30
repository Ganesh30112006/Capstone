-- Baseline schema for auth_db.

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    email         VARCHAR(180) NOT NULL UNIQUE,
    -- BCrypt digest. The plaintext password is never stored, logged, or returned.
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP
);

-- Login looks up by email on every attempt; the UNIQUE constraint already
-- provides the index, so no separate one is needed.

-- Used by the admin console's user list, which filters by role.
CREATE INDEX IF NOT EXISTS idx_users_role ON users (role);
