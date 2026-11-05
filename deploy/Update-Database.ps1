[CmdletBinding()]
param([string]$DbHost = 'localhost\SQLEXPRESS')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$requiredFiles = @(
    'migration_password_recovery.sql', 'migration_order_payment.sql', 'migration_product_featured.sql',
    'migration_order_fulfillment.sql', 'migration_wallet.sql', 'migration_card_keys.sql',
    'migration_support.sql', 'migration_support_protocol.sql', 'migration_categories.sql'
)
foreach ($file in $requiredFiles + 'deploy\upgrade.sql') {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $file) -PathType Leaf)) {
        throw "Missing release file: $file. Extract the entire release ZIP into one folder."
    }
}
Get-Command sqlcmd -ErrorAction Stop | Out-Null
if (Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue) {
    throw 'Port 8081 is still listening. Stop NovaMart with Ctrl+C before updating the database.'
}

# Windows authentication uses the current Windows administrator's SQL rights.
# Back up inside SQL Server's own backup directory, where its service can write.
$backupSql = @'
SET NOCOUNT ON;
IF DB_ID(N'online_shop') IS NULL
    THROW 51000, 'online_shop was not found. Check the SQL instance.', 1;
DECLARE @directory NVARCHAR(4000) = CONVERT(NVARCHAR(4000), SERVERPROPERTY('InstanceDefaultBackupPath'));
IF NULLIF(@directory, N'') IS NULL
    THROW 51000, 'SQL Server default backup directory is not configured. Create a verified backup before upgrading.', 1;
IF RIGHT(@directory, 1) NOT IN (N'\', N'/') SET @directory += N'\';
DECLARE @backupFile NVARCHAR(4000) = @directory + N'online_shop_before_novamart_' +
    CONVERT(NVARCHAR(8), GETDATE(), 112) + N'_' + REPLACE(CONVERT(NVARCHAR(36), NEWID()), N'-', N'') + N'.bak';
BACKUP DATABASE online_shop TO DISK = @backupFile WITH COPY_ONLY, CHECKSUM;
RESTORE VERIFYONLY FROM DISK = @backupFile WITH CHECKSUM;
SELECT @backupFile AS verified_backup_file;
'@

Write-Host 'Creating and verifying a SQL backup...'
& sqlcmd -S $DbHost -E -C -I -b -r 1 -d master -Q $backupSql
if ($LASTEXITCODE -ne 0) { throw 'Backup failed. No database migrations were run.' }

Push-Location $projectRoot
try {
    Write-Host 'Updating NovaMart database...'
    & sqlcmd -S $DbHost -E -C -I -b -r 1 -f 65001 -i '.\deploy\upgrade.sql'
    if ($LASTEXITCODE -ne 0) { throw 'Database update failed. Do not start the new JAR until the error is resolved.' }
    Write-Host 'NOVAMART_DATABASE_UPDATE_OK - database update completed.' -ForegroundColor Green
} finally {
    Pop-Location
}
