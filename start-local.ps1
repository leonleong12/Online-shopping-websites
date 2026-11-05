$ErrorActionPreference = 'Stop'

$jarPath = Join-Path $PSScriptRoot 'target\online-shop-0.0.1-SNAPSHOT.jar'
$javaCandidates = @()
if ($env:JAVA_HOME) {
  $javaCandidates += Join-Path $env:JAVA_HOME 'bin\java.exe'
}
$javaCandidates += 'D:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe'
$javaExe = $javaCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $javaExe) {
  throw 'Java 25 was not found. Install JDK 25 or set JAVA_HOME.'
}
if (-not (Test-Path $jarPath)) {
  throw "Application JAR not found: $jarPath"
}

$env:DB_HOST = 'localhost' + [char]92 + 'SQLEXPRESS01'
$env:DB_USER = 'online_shop_app'
$env:SHOP_PUBLIC_URL = 'http://localhost:8081'

$securePassword = Read-Host 'Enter database password (input is hidden)' -AsSecureString
$passwordPointer = [IntPtr]::Zero
try {
  $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
  $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
} finally {
  if ($passwordPointer -ne [IntPtr]::Zero) {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
  }
  $securePassword.Dispose()
}

try {
  Write-Host 'Starting NovaMart. When startup completes, open http://localhost:8081'
  & $javaExe -jar $jarPath
} finally {
  Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
}
