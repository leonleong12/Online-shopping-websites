-- Run with a database administrator. Safe to run more than once; preserves orders.
USE online_shop;
GO
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF COL_LENGTH(N'dbo.orders', N'delivery_type') IS NULL
    ALTER TABLE dbo.orders ADD delivery_type NVARCHAR(20) NULL;
IF COL_LENGTH(N'dbo.orders', N'tracking_number') IS NULL
    ALTER TABLE dbo.orders ADD tracking_number NVARCHAR(100) NULL;
IF COL_LENGTH(N'dbo.orders', N'virtual_code') IS NULL
    ALTER TABLE dbo.orders ADD virtual_code NVARCHAR(2000) NULL;
IF COL_LENGTH(N'dbo.orders', N'shipped_at') IS NULL
    ALTER TABLE dbo.orders ADD shipped_at DATETIME2 NULL;
IF COL_LENGTH(N'dbo.orders', N'completed_at') IS NULL
    ALTER TABLE dbo.orders ADD completed_at DATETIME2 NULL;
UPDATE dbo.orders SET status=N'PROCESSING',updated_at=SYSUTCDATETIME()
WHERE status=N'PAID' AND payment_status=N'PAID';
COMMIT TRANSACTION;
GO
