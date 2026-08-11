. (Join-Path $PSScriptRoot 'common.ps1')

Assert-Command mvn
Push-Location (Get-DemoRoot)
try {
    mvn -B -ntp verify
    if ($LASTEXITCODE -ne 0) { throw 'Verification failed.' }
} finally {
    Pop-Location
}
