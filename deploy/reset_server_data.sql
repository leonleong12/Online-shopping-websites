-- Destructive one-time reset. This keeps the schema and deletes all store data.
-- The script makes and verifies a full backup before deleting anything.
USE online_shop;
GO

IF DB_NAME() <> N'online_shop'
    THROW 51000, 'Refusing to reset a database other than online_shop.', 1;
IF N'$(ConfirmReset)' <> N'WIPE_ONLINE_SHOP'
    THROW 51000, 'Reset not confirmed. Pass -v ConfirmReset=WIPE_ONLINE_SHOP to sqlcmd.', 1;
GO

DECLARE @backupDirectory NVARCHAR(4000) = CONVERT(NVARCHAR(4000), SERVERPROPERTY('InstanceDefaultBackupPath'));
IF NULLIF(@backupDirectory, N'') IS NULL
    THROW 51000, 'SQL Server has no default backup directory. No data was deleted.', 1;
IF RIGHT(@backupDirectory, 1) NOT IN (N'\', N'/') SET @backupDirectory += N'\';
DECLARE @backupFile NVARCHAR(4000) = @backupDirectory + N'online_shop_before_reset_' +
    CONVERT(NVARCHAR(8), GETDATE(), 112) + N'_' + REPLACE(CONVERT(NVARCHAR(36), NEWID()), N'-', N'') + N'.bak';
BACKUP DATABASE online_shop TO DISK = @backupFile WITH COPY_ONLY, CHECKSUM;
RESTORE VERIFYONLY FROM DISK = @backupFile WITH CHECKSUM;
PRINT N'Verified backup created: ' + @backupFile;
GO

SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;

    DELETE FROM dbo.password_reset_tokens;
    DELETE FROM dbo.password_reset_limits;
    DELETE FROM dbo.chat_messages;
    DELETE FROM dbo.chat_sessions;
    DELETE FROM dbo.support_tickets;
    DELETE FROM dbo.card_keys;
    DELETE FROM dbo.wallet_transactions;
    DELETE FROM dbo.recharge_codes;
    DELETE FROM dbo.recharge_batches;
    DELETE FROM dbo.wallet_redeem_limits;
    DELETE FROM dbo.payments;
    DELETE FROM dbo.order_items;
    DELETE FROM dbo.orders;
    DELETE FROM dbo.cart_items;
    DELETE FROM dbo.products;
    DELETE FROM dbo.categories;
    DELETE FROM dbo.site_settings;
    DELETE FROM dbo.users;

    UPDATE dbo.support_config
    SET admin_online = 0,
        ai_enabled = 0,
        ai_base_url = N'',
        ai_api_key = NULL,
        ai_model = N'',
        ai_protocol = N'CHAT_COMPLETIONS',
        welcome_message = CONVERT(NVARCHAR(500), 0xa8607d590cff226bce8f6567305220004e006f00760061004d006100720074000230ca4e2959f3603a4ee565385efb6d6e7fb970c04e484e7d5969721fff)
    WHERE id = 1;

    COMMIT TRANSACTION;
    PRINT 'NOVAMART_DATA_RESET_OK';
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO
