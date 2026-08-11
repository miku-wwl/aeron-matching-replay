$ErrorActionPreference = 'Stop'

function Get-DemoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

function Get-DemoEnvironmentPath {
    return (Join-Path (Get-DemoRoot) '.env.local')
}

function New-DemoPassword {
    $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Get-DemoEnvironment {
    param([switch] $CreateIfMissing)

    $path = Get-DemoEnvironmentPath
    if (-not (Test-Path -LiteralPath $path)) {
        if (-not $CreateIfMissing) {
            throw "Missing $path. Run scripts/up-infra.ps1 first."
        }
        $lines = @(
            'POSTGRES_DB=jobdemo',
            'POSTGRES_USER=jobdemo',
            "POSTGRES_PASSWORD=$(New-DemoPassword)",
            'RABBITMQ_USERNAME=jobdemo',
            "RABBITMQ_PASSWORD=$(New-DemoPassword)"
        )
        [IO.File]::WriteAllLines($path, $lines, [Text.UTF8Encoding]::new($false))
        Write-Host 'Created local credentials in .env.local (ignored by Git).'
    }

    $values = @{}
    foreach ($rawLine in Get-Content -LiteralPath $path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Invalid environment line in ${path}: $line" }
        $values[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
    }

    $required = @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
        'RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD')
    foreach ($name in $required) {
        if (-not $values.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($values[$name])) {
            throw "Required value $name is missing from $path."
        }
    }
    foreach ($name in @('POSTGRES_DB', 'POSTGRES_USER', 'RABBITMQ_USERNAME')) {
        if ($values[$name] -notmatch '^[A-Za-z0-9_]+$') {
            throw "$name must contain only letters, digits, and underscores."
        }
    }
    foreach ($name in @('POSTGRES_PASSWORD', 'RABBITMQ_PASSWORD')) {
        if ($values[$name] -notmatch '^[A-Za-z0-9]+$') {
            throw "$name must contain only letters and digits."
        }
    }

    return [pscustomobject]@{
        Path = $path
        PostgresDatabase = $values['POSTGRES_DB']
        PostgresUser = $values['POSTGRES_USER']
        PostgresPassword = $values['POSTGRES_PASSWORD']
        RabbitUsername = $values['RABBITMQ_USERNAME']
        RabbitPassword = $values['RABBITMQ_PASSWORD']
    }
}

function Sync-DemoInfrastructureCredentials {
    param([Parameter(Mandatory)] $DemoEnvironment)

    $alterRole = "ALTER ROLE `"$($DemoEnvironment.PostgresUser)`" " +
        "WITH PASSWORD '$($DemoEnvironment.PostgresPassword)';"
    $alterRole | docker exec -i job-demo-postgres psql -v ON_ERROR_STOP=1 `
        -U $DemoEnvironment.PostgresUser -d $DemoEnvironment.PostgresDatabase | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not synchronize the Postgres password.' }

    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        docker exec job-demo-rabbitmq rabbitmq-diagnostics -q check_running *> $null
        if ($LASTEXITCODE -eq 0) {
            docker exec job-demo-rabbitmq rabbitmqctl change_password `
                $DemoEnvironment.RabbitUsername $DemoEnvironment.RabbitPassword | Out-Null
            if ($LASTEXITCODE -eq 0) { return }
        }
        Start-Sleep -Seconds 2
    }
    throw 'Could not synchronize the RabbitMQ password after waiting for RabbitMQ startup.'
}

function Set-DemoKubernetesSecrets {
    param([Parameter(Mandatory)] $DemoEnvironment)

    $demoSecret = kubectl create secret generic demo-secret -n job-demo `
        --from-literal="SPRING_DATASOURCE_USERNAME=$($DemoEnvironment.PostgresUser)" `
        --from-literal="SPRING_DATASOURCE_PASSWORD=$($DemoEnvironment.PostgresPassword)" `
        --from-literal="SPRING_RABBITMQ_USERNAME=$($DemoEnvironment.RabbitUsername)" `
        --from-literal="SPRING_RABBITMQ_PASSWORD=$($DemoEnvironment.RabbitPassword)" `
        --dry-run=client -o yaml
    if ($LASTEXITCODE -ne 0) { throw 'Could not render demo-secret.' }
    $demoSecret | kubectl apply -f - | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not apply demo-secret.' }

    $kedaSecret = kubectl create secret generic keda-rabbitmq-secret -n job-demo `
        --from-literal='host=amqp://host.k3d.internal:15673/%2f' `
        --from-literal="username=$($DemoEnvironment.RabbitUsername)" `
        --from-literal="password=$($DemoEnvironment.RabbitPassword)" `
        --dry-run=client -o yaml
    if ($LASTEXITCODE -ne 0) { throw 'Could not render keda-rabbitmq-secret.' }
    $kedaSecret | kubectl apply -f - | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not apply keda-rabbitmq-secret.' }
}

function Update-ProcessPath {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machinePath;$userPath"
}

function Assert-Command([string] $Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found. Run scripts/prerequisites.ps1 first."
    }
}

function Use-DemoKubectlContext {
    kubectl config use-context k3d-job-demo | Out-Null
}
