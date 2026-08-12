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
            'RABBITMQ_USERNAME=jobdemo',
            "RABBITMQ_PASSWORD=$(New-DemoPassword)"
        )
        [IO.File]::WriteAllLines($path, $lines, [Text.UTF8Encoding]::new($false))
        Write-Host 'Created local RabbitMQ credentials in .env.local (ignored by Git).'
    }

    $values = @{}
    foreach ($rawLine in Get-Content -LiteralPath $path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Invalid environment line in ${path}: $line" }
        $values[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
    }

    foreach ($name in @('RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD')) {
        if (-not $values.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($values[$name])) {
            throw "Required value $name is missing from $path."
        }
    }
    if ($values['RABBITMQ_USERNAME'] -notmatch '^[A-Za-z0-9_]+$') {
        throw 'RABBITMQ_USERNAME must contain only letters, digits, and underscores.'
    }
    if ($values['RABBITMQ_PASSWORD'] -notmatch '^[A-Za-z0-9]+$') {
        throw 'RABBITMQ_PASSWORD must contain only letters and digits.'
    }

    return [pscustomobject]@{
        Path = $path
        RabbitUsername = $values['RABBITMQ_USERNAME']
        RabbitPassword = $values['RABBITMQ_PASSWORD']
    }
}

function Sync-DemoInfrastructureCredentials {
    param([Parameter(Mandatory)] $DemoEnvironment)

    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        docker exec job-demo-rabbitmq rabbitmq-diagnostics -q check_running *> $null
        if ($LASTEXITCODE -eq 0) {
            docker exec job-demo-rabbitmq rabbitmqctl change_password `
                $DemoEnvironment.RabbitUsername $DemoEnvironment.RabbitPassword | Out-Null
            if ($LASTEXITCODE -eq 0) { return }
        }
        Start-Sleep -Seconds 2
    }
    throw 'Could not synchronize the RabbitMQ password after waiting for startup.'
}

function Set-DemoKubernetesSecrets {
    param([Parameter(Mandatory)] $DemoEnvironment)

    $demoSecret = kubectl create secret generic demo-secret -n job-demo `
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
