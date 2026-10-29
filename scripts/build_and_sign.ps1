#Requires -Version 5.1
<#
.SYNOPSIS
Download an unsigned release build, sign locally, and create a draft GitHub release.
.EXAMPLE
.\scripts\build_and_sign.ps1 2026102901 40929118357
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidatePattern('^[0-9]+$')]
    [string]$Tag,

    [Parameter(Position = 1)]
    [ValidatePattern('^[0-9]+$')]
    [string]$RunId
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# Check native exit codes explicitly on both Windows PowerShell and PowerShell 7.
$PSNativeCommandUseErrorActionPreference = $false

function Get-Setting([string]$Name, [string]$Default) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrEmpty($value)) { return $Default }
    return $value
}

function Invoke-Gh {
    $result = & gh @args
    if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI command failed; see its output above.' }
    return $result
}

$root = Split-Path $PSScriptRoot -Parent
$repo = Get-Setting 'GH_REPO' 'TrackerControl/tracker-control-android'
$workflow = Get-Setting 'RELEASE_WORKFLOW' 'release-build.yml'
$keystore = Get-Setting 'RELEASE_STORE_FILE' (Join-Path $root 'trackercontrol.jks')
$keyAlias = Get-Setting 'RELEASE_KEY_ALIAS' 'trackercontrol'
$expectedCert = Get-Setting 'RELEASE_CERT_SHA256' '7f48f74781632067cae334734ff2bdf54eb2a977c061a327499ca9109202d680'
$sdk = Get-Setting 'ANDROID_HOME' (Get-Setting 'ANDROID_SDK_ROOT' (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
$toolsVersion = Get-Setting 'RELEASE_BUILD_TOOLS_VERSION' '36.1.0'
$tools = Join-Path $sdk "build-tools\$toolsVersion"
$apksigner = Join-Path $tools 'apksigner.bat'
$zipalign = Join-Path $tools 'zipalign.exe'
$output = [IO.Path]::GetFullPath((Get-Setting 'RELEASE_OUTPUT_DIR' (Join-Path $root "output\release-$Tag")))
$apkName = 'TrackerControl-githubRelease-latest.apk'
$signed = Join-Path $output $apkName

Get-Command gh -ErrorAction Stop | Out-Null
if ($env:JAVA_HOME) {
    if (!(Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe" -PathType Leaf)) {
        throw 'JAVA_HOME does not contain bin\java.exe.'
    }
} else {
    Get-Command java -ErrorAction Stop | Out-Null
}
foreach ($file in @($keystore, $apksigner, $zipalign)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required file not found: $file" }
}
foreach ($file in @($signed, "$signed.sha256sums", "$signed.build-info.txt")) {
    if (Test-Path -LiteralPath $file) { throw "Output already exists; refusing to overwrite: $file" }
}
Invoke-Gh auth status | Out-Null
$tagSha = (Invoke-Gh api "repos/$repo/commits/$Tag" --jq '.sha' | Out-String).Trim()
if ($tagSha -notmatch '^[0-9a-f]{40}$') { throw 'Could not resolve the release tag.' }
# Confirm this is an existing tag, rather than a numeric branch or abbreviated SHA.
Invoke-Gh api "repos/$repo/git/ref/tags/$Tag" | Out-Null
$releaseTags = @(Invoke-Gh api "repos/$repo/releases" --paginate --jq '.[].tag_name')
if ($releaseTags -contains $Tag) { throw "Release $Tag already exists; refusing to overwrite assets." }

if (!$RunId) {
    $RunId = (Invoke-Gh run list --repo $repo --workflow $workflow --commit $tagSha `
        --status success --limit 10 --json databaseId --jq '.[0].databaseId' | Out-String).Trim()
}
if ($RunId -notmatch '^[0-9]+$') { throw "No successful $workflow run found for $tagSha." }
$run = Invoke-Gh run view $RunId --repo $repo --json headSha,event,status,conclusion,url | ConvertFrom-Json
if ($run.status -ne 'completed' -or $run.conclusion -ne 'success') {
    throw "Workflow run is not successful: $($run.url)"
}
# workflow_dispatch can check out a different ref from the workflow's own head.
# The downloaded BUILD-INFO commit must match the tag in both cases.
if ($run.event -ne 'workflow_dispatch' -and $run.headSha -ne $tagSha) {
    throw "Workflow run built $($run.headSha), but tag $Tag resolves to $tagSha."
}

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temp = Join-Path $tempRoot ("trackercontrol-release-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $artifact = Join-Path $temp 'artifact'
    Invoke-Gh run download $RunId --repo $repo --name "trackercontrol-github-release-$tagSha" --dir $artifact
    $unsigned = Join-Path $artifact 'TrackerControl-githubRelease-unsigned.apk'
    $sums = Join-Path $artifact 'SHA256SUMS-unsigned.txt'
    $buildInfo = Join-Path $artifact 'BUILD-INFO.txt'
    foreach ($file in @($unsigned, $sums, $buildInfo)) {
        if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Artifact file missing: $file" }
    }
    if (!(Select-String -LiteralPath $buildInfo -Pattern "^commit=$tagSha`$" -Quiet)) {
        throw 'Build information does not identify the tag commit.'
    }
    $checksum = (Get-Content -LiteralPath $sums -Raw).Trim()
    if ($checksum -notmatch '^([0-9a-fA-F]{64})\s+\*?TrackerControl-githubRelease-unsigned\.apk$') {
        throw 'Unexpected unsigned checksum file format.'
    }
    if ((Get-FileHash -LiteralPath $unsigned -Algorithm SHA256).Hash -ine $Matches[1]) {
        throw 'Unsigned APK checksum mismatch.'
    }

    $aligned = Join-Path $temp 'aligned.apk'
    $tempSigned = Join-Path $temp 'signed.apk'
    & $zipalign -P 16 -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    $signArgs = @('sign', '--ks', $keystore, '--ks-key-alias', $keyAlias)
    # Otherwise apksigner prompts in the terminal; no passwords enter shell history.
    if ($env:RELEASE_STORE_PASSWORD) { $signArgs += @('--ks-pass', 'env:RELEASE_STORE_PASSWORD') }
    if ($env:RELEASE_KEY_PASSWORD) { $signArgs += @('--key-pass', 'env:RELEASE_KEY_PASSWORD') }
    $signArgs += @('--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false',
        '--out', $tempSigned, $aligned)
    & $apksigner @signArgs
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    $verification = & $apksigner verify --verbose --print-certs $tempSigned
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $verification | Write-Host
    $certLine = $verification | Select-String '^Signer #1 certificate SHA-256 digest:' | Select-Object -First 1
    if (!$certLine) { throw 'No signing certificate found.' }
    $actualCert = ($certLine.ToString() -split ': ', 2)[1].Trim()
    if ($actualCert -ine $expectedCert) { throw "Unexpected signing certificate: $actualCert" }
    & $zipalign -c -P 16 4 $tempSigned
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }

    New-Item -ItemType Directory -Force -Path $output | Out-Null
    # Copy only the verified APK; preserve any output created concurrently.
    [IO.File]::Copy($tempSigned, $signed, $false)
    $hash = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $apkName" | Set-Content -LiteralPath "$signed.sha256sums" -Encoding ASCII
    $metadata = @(Get-Content -LiteralPath $buildInfo)
    $metadata += "signing_certificate_sha256=$actualCert"
    $metadata += "workflow_run=$($run.url)"
    $metadata | Set-Content -LiteralPath "$signed.build-info.txt" -Encoding ASCII

    $notes = "Download the latest version [here](https://github.com/$repo/releases/latest/download/$apkName)."
    $changelog = Join-Path $root "fastlane\metadata\android\en-US\changelogs\$Tag.txt"
    if (Test-Path -LiteralPath $changelog) { $notes += "`n`n" + (Get-Content -LiteralPath $changelog -Raw) }
    $notesFile = Join-Path $temp 'release-notes.txt'
    $notes | Set-Content -LiteralPath $notesFile -Encoding UTF8
    Invoke-Gh release create $Tag $signed "$signed.sha256sums" "$signed.build-info.txt" `
        --repo $repo --verify-tag --draft --generate-notes --notes-file $notesFile --title "Version $Tag"
    Write-Host "Signed APK: $signed"
    Write-Host 'A draft release was created. Test the APK, then publish it in GitHub.'
} finally {
    # Only remove the uniquely generated temporary directory under the OS temp root.
    $resolvedTemp = [IO.Path]::GetFullPath($temp)
    if ($resolvedTemp.StartsWith($tempRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) `
            -and (Split-Path $resolvedTemp -Leaf) -match '^trackercontrol-release-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
