$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$schemaPath = Join-Path $projectRoot 'migration_support.sql'
$migrationPath = Join-Path $projectRoot 'src/main/java/com/onlineshop/SupportDefaultsMigration.java'
$schema = Get-Content -Raw -Encoding UTF8 $schemaPath
$tableStart = $schema.IndexOf('    CREATE TABLE dbo.support_config')
$tableEnd = $schema.IndexOf('END;', $tableStart)
if ($tableStart -lt 0 -or $tableEnd -lt 0) { throw 'Could not locate support_config table definition.' }
$tableSql = $schema.Substring($tableStart, $tableEnd - $tableStart).Replace('dbo.support_config', '#support_config')
$migration = Get-Content -Raw -Encoding UTF8 $migrationPath
$updateBlocks = [regex]::Matches($migration, '(?s)db\.update\("""\s*(.*?)\s*"""')
if ($updateBlocks.Count -ne 2) { throw 'Expected two support-default migration SQL blocks.' }
$defaultsSql = $updateBlocks[0].Groups[1].Value.Replace('dbo.support_config', '#support_config')
$welcomeSql = $updateBlocks[1].Groups[1].Value.Replace('dbo.support_config', '#support_config')
$welcomeMatch = [regex]::Match($migration, 'static final String WELCOME = "([^"]+)";')
if (-not $welcomeMatch.Success) { throw 'Could not locate the canonical welcome message.' }
$welcome = $welcomeMatch.Groups[1].Value
$corruptedWelcome = [Text.Encoding]::GetEncoding(936).GetString([Text.Encoding]::UTF8.GetBytes($welcome))
$repairMatch = [regex]::Match($migration, 'WELCOME,\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)"\s*\);')
if (-not $repairMatch.Success) { throw 'Could not locate the legacy greeting repair patterns.' }
$parameterValues = @($welcome)
$parameterValues += @($repairMatch.Groups[1].Value, $repairMatch.Groups[2].Value, $repairMatch.Groups[3].Value)
foreach ($value in $parameterValues) {
    $position = $welcomeSql.IndexOf('?')
    $welcomeSql = $welcomeSql.Substring(0, $position) + "N'" + $value.Replace("'", "''") + "'" + $welcomeSql.Substring($position + 1)
}

$sql = @"
SET NOCOUNT ON;
$tableSql
INSERT INTO #support_config(id) VALUES(1);
IF EXISTS(SELECT 1 FROM #support_config WHERE ai_base_url<>N'' OR ai_model<>N'' OR ai_protocol<>N'CHAT_COMPLETIONS'
 OR welcome_message<>CONVERT(NVARCHAR(500), 0xa8607d590cff226bce8f6567305220004e006f00760061004d006100720074000230ca4e2959f3603a4ee565385efb6d6e7fb970c04e484e7d5969721fff))
 THROW 51000, 'Fresh defaults or Unicode greeting failed', 1;
UPDATE #support_config SET ai_base_url=N'https://api.aijws.com/v1',ai_model=N'qwen3.8-flash',
 ai_protocol=N'RESPONSES',welcome_message=N'$($corruptedWelcome.Replace("'", "''"))';
$defaultsSql;
$welcomeSql;
IF EXISTS(SELECT 1 FROM #support_config WHERE ai_base_url<>N'' OR ai_model<>N'' OR ai_protocol<>N'CHAT_COMPLETIONS'
 OR welcome_message<>CONVERT(NVARCHAR(500), 0xa8607d590cff226bce8f6567305220004e006f00760061004d006100720074000230ca4e2959f3603a4ee565385efb6d6e7fb970c04e484e7d5969721fff))
 THROW 51000, 'Legacy defaults or corrupted greeting repair failed', 1;
$defaultsSql;
$welcomeSql;
UPDATE #support_config SET ai_base_url=N'https://custom.example/v1',ai_model=N'custom-model',
 ai_protocol=N'RESPONSES',ai_api_key=N'fake-test-key',welcome_message=N'Custom NovaMart welcome';
$defaultsSql;
$welcomeSql;
IF EXISTS(SELECT 1 FROM #support_config WHERE ai_base_url<>N'https://custom.example/v1'
 OR ai_model<>N'custom-model' OR ai_protocol<>N'RESPONSES' OR welcome_message<>N'Custom NovaMart welcome')
 THROW 51000, 'Custom settings were overwritten', 1;
PRINT 'PASS: fresh defaults, Unicode greeting, legacy repair, idempotency, and custom configuration preservation.';
"@

$tempSqlPath = Join-Path ([IO.Path]::GetTempPath()) ('novamart-support-defaults-' + [Guid]::NewGuid().ToString('N') + '.sql')
$exitCode = 1
try {
    [IO.File]::WriteAllText($tempSqlPath, $sql, [Text.UTF8Encoding]::new($false))
    & sqlcmd -S 'localhost\SQLEXPRESS01' -E -C -I -b -l 5 -f 65001 -i $tempSqlPath
    $exitCode = $LASTEXITCODE
} finally {
    Remove-Item -LiteralPath $tempSqlPath -Force -ErrorAction SilentlyContinue
}
exit $exitCode
