-- Additive upgrade: preserve all category names, product assignments and store data.
IF OBJECT_ID(N'dbo.categories', N'U') IS NULL
    THROW 51000, 'Existing categories table was not found. Check the database.', 1;
GO
IF COL_LENGTH(N'dbo.categories', N'sort_order') IS NULL
    ALTER TABLE dbo.categories ADD sort_order INT NOT NULL
        CONSTRAINT df_categories_sort_order DEFAULT 0 WITH VALUES;
GO
IF COL_LENGTH(N'dbo.categories', N'is_visible') IS NULL
    ALTER TABLE dbo.categories ADD is_visible BIT NOT NULL
        CONSTRAINT df_categories_is_visible DEFAULT 1 WITH VALUES;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name=N'chk_categories_sort_order' AND parent_object_id=OBJECT_ID(N'dbo.categories'))
    ALTER TABLE dbo.categories WITH CHECK ADD CONSTRAINT chk_categories_sort_order CHECK (sort_order BETWEEN 0 AND 1000000);
GO
IF DATABASE_PRINCIPAL_ID(N'online_shop_app') IS NOT NULL
    GRANT SELECT, INSERT, UPDATE ON dbo.categories TO online_shop_app;
GO
