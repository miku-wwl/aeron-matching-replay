[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [long]$RecordingId,

    [string]$CheckpointKey = "default",

    [Nullable[long]]$StopPosition,

    [Nullable[long]]$ExpectedLastEventSequence,

    [string]$ExpectedStateHash,

    [string]$CorrelationId,

    [string]$ServiceUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$body = @{
    recordingId = $RecordingId
    checkpointKey = $CheckpointKey
}

if ($null -ne $StopPosition) {
    $body.stopPosition = $StopPosition.Value
}
if ($null -ne $ExpectedLastEventSequence) {
    $body.expectedLastEventSequence = $ExpectedLastEventSequence.Value
}
if ($ExpectedStateHash) {
    $body.expectedStateHash = $ExpectedStateHash
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
