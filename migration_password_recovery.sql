-- NovaMart password recovery migration. Safe to run repeatedly.
USE online_shop;
GO
SET XACT_ABORT ON;
IF OBJECT_ID(N'dbo.password_reset_tokens', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.password_reset_tokens (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_password_reset_tokens PRIMARY KEY,
        user_id BIGINT NOT NULL,
        token_hash CHAR(64) NOT NULL CONSTRAINT uq_password_reset_token UNIQUE,
        expires_at DATETIME2 NOT NULL,
        consumed_at DATETIME2 NULL,
        created_at DATETIME2 NOT NULL CONSTRAINT df_password_reset_created DEFAULT SYSUTCDATETIME(),
        CONSTRAINT fk_password_reset_user FOREIGN KEY(user_id) REFERENCES dbo.users(id) ON DELETE CASCADE
    );
    CREATE INDEX idx_password_reset_user ON dbo.password_reset_tokens(user_id);
END;
GO
IF OBJECT_ID(N'dbo.password_reset_limits', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.password_reset_limits (
        scope_key CHAR(64) NOT NULL CONSTRAINT pk_password_reset_limits PRIMARY KEY,
        window_started_at DATETIME2 NOT NULL,
        attempts INT NOT NULL CONSTRAINT chk_password_reset_attempts CHECK(attempts > 0),
        next_allowed_at DATETIME2 NOT NULL
    );
END;
GO
IF DATABASE_PRINCIPAL_ID(N'online_shop_app') IS NOT NULL
BEGIN
    GRANT SELECT, INSERT, UPDATE, DELETE ON dbo.password_reset_tokens TO online_shop_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON dbo.password_reset_limits TO online_shop_app;
END;
GO
