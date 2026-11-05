USE online_shop;
GO

IF COL_LENGTH(N'dbo.orders', N'recipient_name') IS NULL
    ALTER TABLE dbo.orders ADD recipient_name NVARCHAR(80) NULL;
GO

IF COL_LENGTH(N'dbo.orders', N'recipient_phone') IS NULL
    ALTER TABLE dbo.orders ADD recipient_phone NVARCHAR(20) NULL;
GO

IF COL_LENGTH(N'dbo.orders', N'payment_deadline') IS NULL
BEGIN
    ALTER TABLE dbo.orders ADD payment_deadline DATETIME2 NULL;
END;
GO

UPDATE dbo.orders SET payment_deadline=DATEADD(MINUTE,15,created_at) WHERE payment_deadline IS NULL;
GO

IF EXISTS (SELECT 1 FROM sys.columns WHERE object_id=OBJECT_ID(N'dbo.orders') AND name=N'payment_deadline' AND is_nullable=1)
BEGIN
    ALTER TABLE dbo.orders ALTER COLUMN payment_deadline DATETIME2 NOT NULL;
END;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.default_constraints dc
    JOIN sys.columns c ON c.object_id=dc.parent_object_id AND c.column_id=dc.parent_column_id
    WHERE dc.parent_object_id=OBJECT_ID(N'dbo.orders') AND c.name=N'payment_deadline'
)
    ALTER TABLE dbo.orders ADD CONSTRAINT df_orders_payment_deadline DEFAULT DATEADD(MINUTE,15,SYSUTCDATETIME()) FOR payment_deadline;
GO

UPDATE dbo.orders SET recipient_name=N'历史订单' WHERE recipient_name IS NULL;
UPDATE dbo.orders SET recipient_phone=N'' WHERE recipient_phone IS NULL;
GO
