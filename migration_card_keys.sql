-- SQL Server migration for automatic product card-key delivery.
-- Run in SSMS against online_shop using a database administrator account.
USE online_shop;
GO
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF COL_LENGTH(N'dbo.products', N'delivery_type') IS NULL
BEGIN
    ALTER TABLE dbo.products ADD delivery_type NVARCHAR(20) NOT NULL
        CONSTRAINT df_products_delivery_type DEFAULT N'PHYSICAL' WITH VALUES;
END;
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name=N'chk_products_delivery_type')
    EXEC(N'ALTER TABLE dbo.products ADD CONSTRAINT chk_products_delivery_type
        CHECK (delivery_type IN (N''PHYSICAL'',N''AUTO_CARD''))');

IF COL_LENGTH(N'dbo.orders', N'delivered_cards') IS NULL
    ALTER TABLE dbo.orders ADD delivered_cards NVARCHAR(MAX) NULL;

IF OBJECT_ID(N'dbo.card_keys', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.card_keys (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_card_keys PRIMARY KEY,
        product_id BIGINT NOT NULL,
        card_content NVARCHAR(MAX) NOT NULL,
        status TINYINT NOT NULL CONSTRAINT df_card_keys_status DEFAULT 0,
        order_no BIGINT NULL,
        buyer_id BIGINT NULL,
        created_at DATETIME2 NOT NULL CONSTRAINT df_card_keys_created DEFAULT SYSUTCDATETIME(),
        sold_at DATETIME2 NULL,
        CONSTRAINT fk_card_keys_product FOREIGN KEY(product_id) REFERENCES dbo.products(id),
        CONSTRAINT fk_card_keys_order FOREIGN KEY(order_no) REFERENCES dbo.orders(id),
        CONSTRAINT fk_card_keys_buyer FOREIGN KEY(buyer_id) REFERENCES dbo.users(id),
        CONSTRAINT chk_card_keys_status CHECK(status IN (0,1)),
        CONSTRAINT chk_card_keys_sale CHECK (
            (status=0 AND order_no IS NULL AND buyer_id IS NULL AND sold_at IS NULL) OR
            (status=1 AND order_no IS NOT NULL AND buyer_id IS NOT NULL AND sold_at IS NOT NULL))
    );
    CREATE INDEX idx_card_keys_available ON dbo.card_keys(product_id,status,id) INCLUDE(card_content);
    CREATE INDEX idx_card_keys_sold_order ON dbo.card_keys(order_no,status);
END;
COMMIT TRANSACTION;
GO

-- The application can manage card-key inventory but should not change schema.
IF DATABASE_PRINCIPAL_ID(N'online_shop_app') IS NOT NULL
    EXEC(N'GRANT SELECT, INSERT, UPDATE, DELETE ON dbo.card_keys TO online_shop_app');
GO
