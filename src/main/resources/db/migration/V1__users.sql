CREATE TABLE app_users (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_app_users_username UNIQUE (username),
    CONSTRAINT ck_app_users_username_lowercase CHECK (username = lower(username)),
    CONSTRAINT ck_app_users_role CHECK (role IN ('ADMIN', 'USER'))
);
