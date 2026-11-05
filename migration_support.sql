USE online_shop;
GO

IF OBJECT_ID(N'dbo.support_config', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.support_config (
        id INT NOT NULL CONSTRAINT pk_support_config PRIMARY KEY,
        admin_online BIT NOT NULL CONSTRAINT df_support_config_admin_online DEFAULT 0,
        ai_enabled BIT NOT NULL CONSTRAINT df_support_config_ai_enabled DEFAULT 1,
        ai_base_url NVARCHAR(500) NOT NULL CONSTRAINT df_support_config_base_url DEFAULT N'https://api.aijws.com/v1',
        ai_api_key NVARCHAR(500) NULL,
        ai_model NVARCHAR(100) NOT NULL CONSTRAINT df_support_config_model DEFAULT N'qwen3.8-flash',
        ai_protocol NVARCHAR(32) NOT NULL CONSTRAINT df_support_config_protocol DEFAULT N'RESPONSES'
            CONSTRAINT chk_support_config_protocol CHECK (ai_protocol IN ('CHAT_COMPLETIONS','RESPONSES','ANTHROPIC_MESSAGES')),
        welcome_message NVARCHAR(500) NOT NULL CONSTRAINT df_support_config_welcome DEFAULT N'您好，欢迎来到 NovaMart。今天想为日常添置点什么好物？',
        CONSTRAINT chk_support_config_singleton CHECK (id = 1)
    );
END;
IF COL_LENGTH(N'dbo.support_config', N'ai_protocol') IS NULL
    ALTER TABLE dbo.support_config ADD ai_protocol NVARCHAR(32) NOT NULL
        CONSTRAINT df_support_config_protocol DEFAULT N'RESPONSES'
        CONSTRAINT chk_support_config_protocol CHECK (ai_protocol IN ('CHAT_COMPLETIONS','RESPONSES','ANTHROPIC_MESSAGES'));
IF NOT EXISTS (SELECT 1 FROM dbo.support_config WHERE id=1)
    INSERT INTO dbo.support_config(id) VALUES(1);
GO

IF OBJECT_ID(N'dbo.chat_sessions', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.chat_sessions (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_chat_sessions PRIMARY KEY,
        user_id BIGINT NOT NULL,
        username NVARCHAR(50) NOT NULL,
        unread_for_admin INT NOT NULL CONSTRAINT df_chat_sessions_unread_admin DEFAULT 0,
        unread_for_user INT NOT NULL CONSTRAINT df_chat_sessions_unread_user DEFAULT 0,
        last_message NVARCHAR(200) NULL,
        updated_at DATETIME2 NOT NULL CONSTRAINT df_chat_sessions_updated DEFAULT SYSUTCDATETIME(),
        created_at DATETIME2 NOT NULL CONSTRAINT df_chat_sessions_created DEFAULT SYSUTCDATETIME(),
        CONSTRAINT uq_chat_sessions_user UNIQUE(user_id),
        CONSTRAINT fk_chat_sessions_user FOREIGN KEY(user_id) REFERENCES dbo.users(id) ON DELETE CASCADE,
        CONSTRAINT chk_chat_sessions_unread CHECK(unread_for_admin >= 0 AND unread_for_user >= 0)
    );
    CREATE INDEX idx_chat_sessions_updated ON dbo.chat_sessions(updated_at DESC);
END;
GO

IF OBJECT_ID(N'dbo.chat_messages', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.chat_messages (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_chat_messages PRIMARY KEY,
        session_id BIGINT NOT NULL,
        user_id BIGINT NOT NULL,
        sender_role NVARCHAR(10) NOT NULL,
        content NVARCHAR(1000) NOT NULL,
        created_at DATETIME2 NOT NULL CONSTRAINT df_chat_messages_created DEFAULT SYSUTCDATETIME(),
        CONSTRAINT fk_chat_messages_session FOREIGN KEY(session_id) REFERENCES dbo.chat_sessions(id) ON DELETE CASCADE,
        CONSTRAINT fk_chat_messages_user FOREIGN KEY(user_id) REFERENCES dbo.users(id),
        CONSTRAINT chk_chat_messages_role CHECK(sender_role IN (N'USER',N'ADMIN',N'AI')),
        CONSTRAINT chk_chat_messages_content CHECK(LEN(content) > 0)
    );
    CREATE INDEX idx_chat_messages_session_id ON dbo.chat_messages(session_id,id DESC);
END;
GO

IF OBJECT_ID(N'dbo.support_tickets', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.support_tickets (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_support_tickets PRIMARY KEY,
        ticket_no NVARCHAR(40) NOT NULL CONSTRAINT uq_support_tickets_no UNIQUE,
        user_id BIGINT NOT NULL,
        username NVARCHAR(50) NOT NULL,
        related_order_no NVARCHAR(40) NULL,
        category NVARCHAR(20) NOT NULL,
        title NVARCHAR(120) NOT NULL,
        content NVARCHAR(3000) NOT NULL,
        status NVARCHAR(20) NOT NULL CONSTRAINT df_support_tickets_status DEFAULT N'OPEN',
        admin_reply NVARCHAR(3000) NULL,
        created_at DATETIME2 NOT NULL CONSTRAINT df_support_tickets_created DEFAULT SYSUTCDATETIME(),
        updated_at DATETIME2 NOT NULL CONSTRAINT df_support_tickets_updated DEFAULT SYSUTCDATETIME(),
        CONSTRAINT fk_support_tickets_user FOREIGN KEY(user_id) REFERENCES dbo.users(id),
        CONSTRAINT chk_support_tickets_category CHECK(category IN (N'PRE_SALE',N'CARD_ISSUE',N'PAYMENT',N'OTHER')),
        CONSTRAINT chk_support_tickets_status CHECK(status IN (N'OPEN',N'REPLIED',N'CLOSED'))
    );
    CREATE INDEX idx_support_tickets_status_created ON dbo.support_tickets(status,created_at DESC);
    CREATE INDEX idx_support_tickets_user_created ON dbo.support_tickets(user_id,created_at DESC);
END;
GO
