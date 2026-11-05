-- Run with a database administrator. Safe to run more than once.
USE online_shop;
GO
IF COL_LENGTH(N'dbo.products', N'is_featured') IS NULL
    ALTER TABLE dbo.products ADD is_featured BIT NOT NULL
        CONSTRAINT df_products_featured DEFAULT 0 WITH VALUES;
GO
