-- Existing NovaMart database only. Run through Update-Database.ps1 / sqlcmd.
:On Error exit
USE online_shop;
GO
SET XACT_ABORT ON;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
IF OBJECT_ID(N'dbo.users', N'U') IS NULL
   OR OBJECT_ID(N'dbo.products', N'U') IS NULL
   OR OBJECT_ID(N'dbo.orders', N'U') IS NULL
   OR OBJECT_ID(N'dbo.order_items', N'U') IS NULL
    THROW 51000, 'Existing NovaMart base tables were not found. Stop and check the SQL instance.', 1;
IF DATABASE_PRINCIPAL_ID(N'online_shop_app') IS NULL
    THROW 51000, 'Database user online_shop_app was not found. Check the SQL instance and existing login mapping.', 1;
BEGIN TRANSACTION;
GO
:r "migration_order_payment.sql"
:r "migration_product_featured.sql"
:r "migration_categories.sql"
:r "migration_order_fulfillment.sql"
:r "migration_wallet.sql"
:r "migration_card_keys.sql"
:r "migration_support.sql"
:r "migration_support_protocol.sql"
:r "migration_password_recovery.sql"
GO
-- New modules need explicit runtime access even on installations that only
-- granted permissions on the original tables. Never grant schema/admin rights.
GRANT SELECT, INSERT ON dbo.wallet_transactions TO online_shop_app;
DENY UPDATE, DELETE ON dbo.wallet_transactions TO online_shop_app;
GRANT SELECT, INSERT, UPDATE ON dbo.recharge_batches TO online_shop_app;
GRANT SELECT, INSERT, UPDATE ON dbo.recharge_codes TO online_shop_app;
GRANT SELECT, INSERT, UPDATE ON dbo.wallet_redeem_limits TO online_shop_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON dbo.card_keys TO online_shop_app;
GRANT SELECT, UPDATE ON dbo.support_config TO online_shop_app;
GRANT SELECT, INSERT, UPDATE ON dbo.chat_sessions TO online_shop_app;
GRANT SELECT, INSERT, UPDATE ON dbo.chat_messages TO online_shop_app;
GRANT SELECT, INSERT, UPDATE ON dbo.support_tickets TO online_shop_app;
GO
IF COL_LENGTH(N'dbo.users', N'balance') IS NULL
   OR COL_LENGTH(N'dbo.products', N'is_featured') IS NULL
   OR COL_LENGTH(N'dbo.categories', N'sort_order') IS NULL
   OR COL_LENGTH(N'dbo.categories', N'is_visible') IS NULL
   OR COL_LENGTH(N'dbo.products', N'delivery_type') IS NULL
   OR COL_LENGTH(N'dbo.orders', N'payment_deadline') IS NULL
   OR COL_LENGTH(N'dbo.orders', N'delivered_cards') IS NULL
   OR COL_LENGTH(N'dbo.support_config', N'ai_protocol') IS NULL
   OR OBJECT_ID(N'dbo.wallet_transactions', N'U') IS NULL
   OR OBJECT_ID(N'dbo.card_keys', N'U') IS NULL
   OR OBJECT_ID(N'dbo.recharge_codes', N'U') IS NULL
   OR OBJECT_ID(N'dbo.support_tickets', N'U') IS NULL
   OR OBJECT_ID(N'dbo.password_reset_tokens', N'U') IS NULL
   OR OBJECT_ID(N'dbo.password_reset_limits', N'U') IS NULL
    THROW 51000, 'Database validation failed. The update will not be committed.', 1;
COMMIT TRANSACTION;
GO
PRINT 'NOVAMART_DATABASE_UPDATE_OK';
GO
