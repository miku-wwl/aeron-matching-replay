[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [long]$RecordingId,

    [string]$CheckpointKey = "default",

    [Nullable[long]]$StopPosition,

    [Parameter(Mandatory)]
    [ValidateRange(0, [long]::MaxValue)]
    [long]$ExpectedLastEventSequence,

    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9]{1,20}$')]
    [string]$ExpectedStateHash,

    [string]$CorrelationId,

    [string]$ServiceUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$body = @{
    recordingId = $RecordingId
    checkpointKey = $CheckpointKey
    expectedLastEventSequence = $ExpectedLastEventSequence
    expectedStateHash = $ExpectedStateHash
}

if ($null -ne $StopPosition) {
    $body.stopPosition = $StopPosition.Value
}
if ($CorrelationId) {
    $body.correlationId = $CorrelationId
}

$response = Invoke-RestMethod `
    -Method Post `
    -Uri "$($ServiceUrl.TrimEnd('/'))/api/v1/replays" `
    -ContentType "application/json" `
    -Body ($body | ConvertTo-Json)

$response | ConvertTo-Json -Depth 8
