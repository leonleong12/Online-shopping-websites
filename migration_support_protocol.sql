USE online_shop;
GO
IF OBJECT_ID(N'dbo.support_config', N'U') IS NOT NULL
   AND COL_LENGTH(N'dbo.support_config', N'ai_protocol') IS NULL
    ALTER TABLE dbo.support_config ADD ai_protocol NVARCHAR(32) NOT NULL
        CONSTRAINT df_support_config_protocol DEFAULT N'CHAT_COMPLETIONS'
        CONSTRAINT chk_support_config_protocol CHECK (ai_protocol IN ('CHAT_COMPLETIONS','RESPONSES','ANTHROPIC_MESSAGES'));
GO
