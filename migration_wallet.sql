-- NovaMart wallet migration: run with a database administrator, safe to run again.
USE online_shop;
GO
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET XACT_ABORT ON;
GO
IF COL_LENGTH(N'dbo.users',N'balance') IS NULL
    ALTER TABLE dbo.users ADD balance DECIMAL(10,2) NOT NULL CONSTRAINT df_users_balance DEFAULT 0 WITH VALUES;
GO
IF OBJECT_ID(N'dbo.chk_users_balance',N'C') IS NULL
    ALTER TABLE dbo.users ADD CONSTRAINT chk_users_balance CHECK(balance>=0);
GO
IF OBJECT_ID(N'dbo.wallet_transactions',N'U') IS NULL
BEGIN
    CREATE TABLE dbo.wallet_transactions (
        id BIGINT IDENTITY PRIMARY KEY,
        user_id BIGINT NOT NULL REFERENCES dbo.users(id),
        type VARCHAR(20) NOT NULL CHECK(type IN ('RECHARGE','CONSUME','REFUND','ADMIN_ADJUST')),
        amount DECIMAL(10,2) NOT NULL,
        balance_after DECIMAL(10,2) NOT NULL CHECK(balance_after>=0),
        related_order_no NVARCHAR(50) NULL,
        remark NVARCHAR(500) NOT NULL,
        actor_id BIGINT NULL REFERENCES dbo.users(id),
        request_key VARCHAR(120) NULL,
        created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT chk_wallet_direction CHECK (
            (type IN ('RECHARGE','REFUND') AND amount>0) OR
            (type='CONSUME' AND amount<=0) OR (type='ADMIN_ADJUST' AND amount<>0))
    );
    CREATE INDEX idx_wallet_user_time ON dbo.wallet_transactions(user_id,id DESC);
    CREATE UNIQUE INDEX uq_wallet_request ON dbo.wallet_transactions(request_key) WHERE request_key IS NOT NULL;
    CREATE UNIQUE INDEX uq_wallet_order_type ON dbo.wallet_transactions(related_order_no,type) WHERE related_order_no IS NOT NULL;
END;
GO
IF OBJECT_ID(N'dbo.recharge_batches',N'U') IS NULL
    CREATE TABLE dbo.recharge_batches (
        request_key VARCHAR(120) PRIMARY KEY,
        created_by BIGINT NOT NULL REFERENCES dbo.users(id),
        amount DECIMAL(10,2) NOT NULL CHECK(amount>0),
        quantity INT NOT NULL CHECK(quantity BETWEEN 1 AND 100),
        created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
GO
IF OBJECT_ID(N'dbo.recharge_codes',N'U') IS NULL
BEGIN
    CREATE TABLE dbo.recharge_codes (
        id BIGINT IDENTITY PRIMARY KEY,
        code VARCHAR(60) NOT NULL UNIQUE,
        amount DECIMAL(10,2) NOT NULL CHECK(amount>0),
        status TINYINT NOT NULL DEFAULT 0 CHECK(status IN (0,1)),
        used_by BIGINT NULL REFERENCES dbo.users(id),
        used_at DATETIME2 NULL,
        batch_key VARCHAR(120) NOT NULL REFERENCES dbo.recharge_batches(request_key),
        created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT chk_recharge_used CHECK (
            (status=0 AND used_by IS NULL AND used_at IS NULL) OR
            (status=1 AND used_by IS NOT NULL AND used_at IS NOT NULL))
    );
    CREATE INDEX idx_recharge_batch ON dbo.recharge_codes(batch_key,id);
END;
GO
IF OBJECT_ID(N'dbo.wallet_redeem_limits',N'U') IS NULL
    CREATE TABLE dbo.wallet_redeem_limits (
        user_id BIGINT PRIMARY KEY REFERENCES dbo.users(id),
        failures INT NOT NULL DEFAULT 0 CHECK(failures BETWEEN 0 AND 5),
        blocked_until DATETIME2 NULL
    );
GO
-- Runtime account can append the audit trail, but cannot change or erase it.
IF DATABASE_PRINCIPAL_ID(N'online_shop_app') IS NOT NULL
    DENY UPDATE, DELETE ON dbo.wallet_transactions TO online_shop_app;
GO

