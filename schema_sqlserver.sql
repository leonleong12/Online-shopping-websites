-- Online shopping site database (SQL Server 2017+)
IF DB_ID(N'online_shop') IS NULL
    EXEC(N'CREATE DATABASE online_shop');
GO

USE online_shop;
GO

IF OBJECT_ID(N'dbo.users', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.users (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_users PRIMARY KEY,
        username NVARCHAR(50) NOT NULL CONSTRAINT uq_users_username UNIQUE,
        password_hash NVARCHAR(255) NOT NULL,
        email NVARCHAR(120) NOT NULL CONSTRAINT uq_users_email UNIQUE,
        role NVARCHAR(20) NOT NULL CONSTRAINT df_users_role DEFAULT N'CUSTOMER',
        status NVARCHAR(20) NOT NULL CONSTRAINT df_users_status DEFAULT N'ACTIVE',
        created_at DATETIME2 NOT NULL CONSTRAINT df_users_created DEFAULT SYSUTCDATETIME(),
        updated_at DATETIME2 NOT NULL CONSTRAINT df_users_updated DEFAULT SYSUTCDATETIME(),
        CONSTRAINT chk_users_role CHECK (role IN (N'CUSTOMER', N'ADMIN')),
        CONSTRAINT chk_users_status CHECK (status IN (N'ACTIVE', N'DISABLED'))
    );
END;
GO

IF OBJECT_ID(N'dbo.categories', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.categories (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_categories PRIMARY KEY,
        name NVARCHAR(80) NOT NULL CONSTRAINT uq_categories_name UNIQUE,
        description NVARCHAR(255) NULL,
        sort_order INT NOT NULL CONSTRAINT df_categories_sort_order DEFAULT 0
            CONSTRAINT chk_categories_sort_order CHECK (sort_order BETWEEN 0 AND 1000000),
        is_visible BIT NOT NULL CONSTRAINT df_categories_is_visible DEFAULT 1,
        created_at DATETIME2 NOT NULL CONSTRAINT df_categories_created DEFAULT SYSUTCDATETIME()
    );
END;
GO

IF OBJECT_ID(N'dbo.site_settings', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.site_settings (
        setting_key NVARCHAR(50) NOT NULL CONSTRAINT pk_site_settings PRIMARY KEY,
        setting_value NVARCHAR(500) NULL,
        updated_at DATETIME2 NOT NULL CONSTRAINT df_site_settings_updated DEFAULT SYSUTCDATETIME()
    );
END;
GO

IF OBJECT_ID(N'dbo.products', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.products (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_products PRIMARY KEY,
        category_id BIGINT NULL,
        name NVARCHAR(150) NOT NULL,
        description NVARCHAR(MAX) NULL,
        price DECIMAL(10,2) NOT NULL,
        stock INT NOT NULL CONSTRAINT df_products_stock DEFAULT 0,
        image_url NVARCHAR(500) NULL,
        is_featured BIT NOT NULL CONSTRAINT df_products_featured DEFAULT 0,
        status NVARCHAR(20) NOT NULL CONSTRAINT df_products_status DEFAULT N'ON_SALE',
        created_at DATETIME2 NOT NULL CONSTRAINT df_products_created DEFAULT SYSUTCDATETIME(),
        updated_at DATETIME2 NOT NULL CONSTRAINT df_products_updated DEFAULT SYSUTCDATETIME(),
        CONSTRAINT chk_products_price CHECK (price >= 0),
        CONSTRAINT chk_products_stock CHECK (stock >= 0),
        CONSTRAINT chk_products_status CHECK (status IN (N'ON_SALE', N'OFF_SALE')),
        CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES dbo.categories(id) ON DELETE SET NULL
    );
    CREATE INDEX idx_products_name ON dbo.products(name);
    CREATE INDEX idx_products_category_status ON dbo.products(category_id, status);
END;
GO

IF COL_LENGTH(N'dbo.products', N'is_featured') IS NULL
    ALTER TABLE dbo.products ADD is_featured BIT NOT NULL
        CONSTRAINT df_products_featured DEFAULT 0 WITH VALUES;
GO

IF OBJECT_ID(N'dbo.cart_items', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.cart_items (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_cart_items PRIMARY KEY,
        user_id BIGINT NOT NULL,
        product_id BIGINT NOT NULL,
        quantity INT NOT NULL,
        created_at DATETIME2 NOT NULL CONSTRAINT df_cart_created DEFAULT SYSUTCDATETIME(),
        updated_at DATETIME2 NOT NULL CONSTRAINT df_cart_updated DEFAULT SYSUTCDATETIME(),
        CONSTRAINT chk_cart_quantity CHECK (quantity > 0),
        CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES dbo.users(id) ON DELETE CASCADE,
        CONSTRAINT fk_cart_product FOREIGN KEY (product_id) REFERENCES dbo.products(id),
        CONSTRAINT uk_cart_user_product UNIQUE (user_id, product_id)
    );
END;
GO

IF OBJECT_ID(N'dbo.orders', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.orders (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_orders PRIMARY KEY,
        user_id BIGINT NOT NULL,
        total_amount DECIMAL(10,2) NOT NULL,
        status NVARCHAR(20) NOT NULL CONSTRAINT df_orders_status DEFAULT N'PENDING_PAYMENT',
        payment_status NVARCHAR(20) NOT NULL CONSTRAINT df_orders_payment DEFAULT N'UNPAID',
        recipient_name NVARCHAR(80) NULL,
        recipient_phone NVARCHAR(20) NULL,
        shipping_address NVARCHAR(500) NOT NULL,
        delivery_type NVARCHAR(20) NULL,
        tracking_number NVARCHAR(100) NULL,
        virtual_code NVARCHAR(2000) NULL,
        shipped_at DATETIME2 NULL,
        completed_at DATETIME2 NULL,
        payment_deadline DATETIME2 NOT NULL CONSTRAINT df_orders_payment_deadline DEFAULT DATEADD(MINUTE,15,SYSUTCDATETIME()),
        created_at DATETIME2 NOT NULL CONSTRAINT df_orders_created DEFAULT SYSUTCDATETIME(),
        updated_at DATETIME2 NOT NULL CONSTRAINT df_orders_updated DEFAULT SYSUTCDATETIME(),
        CONSTRAINT chk_orders_amount CHECK (total_amount >= 0),
        CONSTRAINT chk_orders_status CHECK (status IN (N'PENDING_PAYMENT', N'PAID', N'PROCESSING', N'SHIPPED', N'COMPLETED', N'CANCELLED')),
        CONSTRAINT chk_orders_payment CHECK (payment_status IN (N'UNPAID', N'PAID', N'REFUNDED')),
        CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES dbo.users(id)
    );
    CREATE INDEX idx_orders_user_created ON dbo.orders(user_id, created_at);
    CREATE INDEX idx_orders_status ON dbo.orders(status);
END;
GO

IF COL_LENGTH(N'dbo.orders', N'recipient_name') IS NULL
    ALTER TABLE dbo.orders ADD recipient_name NVARCHAR(80) NULL;
GO
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
IF OBJECT_ID(N'dbo.order_items', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.order_items (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_order_items PRIMARY KEY,
        order_id BIGINT NOT NULL,
        product_id BIGINT NOT NULL,
        product_name NVARCHAR(150) NOT NULL,
        unit_price DECIMAL(10,2) NOT NULL,
        quantity INT NOT NULL,
        subtotal AS (unit_price * quantity) PERSISTED,
        CONSTRAINT chk_order_items_price CHECK (unit_price >= 0),
        CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
        CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES dbo.orders(id) ON DELETE CASCADE,
        CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES dbo.products(id)
    );
    CREATE INDEX idx_order_items_product ON dbo.order_items(product_id);
END;
GO

IF OBJECT_ID(N'dbo.payments', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.payments (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_payments PRIMARY KEY,
        order_id BIGINT NOT NULL CONSTRAINT uq_payments_order UNIQUE,
        payment_method NVARCHAR(30) NOT NULL CONSTRAINT df_payments_method DEFAULT N'SIMULATED',
        payment_amount DECIMAL(10,2) NOT NULL,
        payment_status NVARCHAR(20) NOT NULL CONSTRAINT df_payments_status DEFAULT N'SUCCESS',
        paid_at DATETIME2 NULL,
        created_at DATETIME2 NOT NULL CONSTRAINT df_payments_created DEFAULT SYSUTCDATETIME(),
        CONSTRAINT chk_payments_amount CHECK (payment_amount >= 0),
        CONSTRAINT chk_payments_status CHECK (payment_status IN (N'SUCCESS', N'FAILED', N'REFUNDED')),
        CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES dbo.orders(id) ON DELETE CASCADE
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM dbo.categories WHERE name = N'General')
    INSERT INTO dbo.categories (name, description) VALUES (N'General', N'General products');
GO

IF NOT EXISTS (SELECT 1 FROM dbo.products WHERE name = N'Test product')
BEGIN
    INSERT INTO dbo.products (category_id, name, description, price, stock)
    SELECT id, N'Test product', N'Seed data for local testing', 99.00, 100
    FROM dbo.categories WHERE name = N'General';
END;
GO

CREATE OR ALTER VIEW dbo.sales_summary AS
SELECT CAST(o.created_at AS date) AS sales_date,
       COUNT(DISTINCT o.id) AS order_count,
       COALESCE(SUM(oi.subtotal), 0) AS sales_amount,
       COALESCE(SUM(oi.quantity), 0) AS item_count
FROM dbo.orders o
JOIN dbo.order_items oi ON oi.order_id = o.id
WHERE o.status IN (N'PAID', N'PROCESSING', N'SHIPPED', N'COMPLETED')
GROUP BY CAST(o.created_at AS date);
GO
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

