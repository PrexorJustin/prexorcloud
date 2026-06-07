<#
.SYNOPSIS
    prexorctl installer for Windows (PowerShell 5.1+).

.DESCRIPTION
    Downloads the latest signed prexorctl release for windows/amd64 or
    windows/arm64, verifies SHA-256 (and cosign signature when cosign is on
    PATH), installs to %LOCALAPPDATA%\Programs\prexorctl, adds it to the
    user PATH, and (by default) launches the browser-based setup wizard.

.PARAMETER NoSetup
    Install the binary without launching the setup wizard.

.PARAMETER Version
    Pin a specific release tag (e.g. v1.0.0). Default: latest.

.PARAMETER InstallDir
    Install directory. Default: %LOCALAPPDATA%\Programs\prexorctl
    (no admin required). Pass an absolute path to override.

.EXAMPLE
    irm https://prexor.cloud/install.ps1 | iex

.EXAMPLE
    & ([scriptblock]::Create((irm https://prexor.cloud/install.ps1))) -NoSetup

.EXAMPLE
    & ([scriptblock]::Create((irm https://prexor.cloud/install.ps1))) -Version v1.0.0

.NOTES
    Source artifacts live on the project's GitHub Releases:
      https://github.com/PrexorJustin/prexorcloud/releases
#>

[CmdletBinding()]
param(
    [switch] $NoSetup,
    [string] $Version = 'latest',
    [string] $InstallDir = (Join-Path $env:LOCALAPPDATA 'Programs\prexorctl')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

# ─── Configuration ───────────────────────────────────────────────────────────
$GhRepo               = 'PrexorJustin/prexorcloud'
$ReleasesBase         = "https://github.com/$GhRepo/releases"
$Binary               = 'prexorctl.exe'
$BinaryName           = 'prexorctl'
$CosignIdentityRegex  = '^https://github\.com/PrexorJustin/prexorcloud/\.github/workflows/release\.ya?ml@.*'
$CosignIssuer         = 'https://token.actions.githubusercontent.com'

# ─── Output helpers ──────────────────────────────────────────────────────────
function Write-Step ([string] $msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Warn ([string] $msg) { Write-Host "Warning: $msg" -ForegroundColor Yellow }
function Stop-Install ([string] $msg) {
    Write-Host "Error: $msg" -ForegroundColor Red
    exit 1
}

# ─── TLS — force 1.2+ on Windows PowerShell 5.1, which still negotiates SSL3 ─
[Net.ServicePointManager]::SecurityProtocol =
    [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13

# ─── Platform detection ──────────────────────────────────────────────────────
$arch = switch -Wildcard ($env:PROCESSOR_ARCHITECTURE) {
    'AMD64' { 'amd64' }
    'ARM64' { 'arm64' }
    'x86'   { Stop-Install '32-bit Windows is not supported. prexorctl requires a 64-bit CPU.' }
    default { Stop-Install "Unsupported architecture: $env:PROCESSOR_ARCHITECTURE" }
}

# WOW64: 32-bit PowerShell on 64-bit Windows reports x86 in PROCESSOR_ARCHITECTURE.
# PROCESSOR_ARCHITEW6432 is the truth in that case.
if ($env:PROCESSOR_ARCHITEW6432) {
    $arch = switch ($env:PROCESSOR_ARCHITEW6432) {
        'AMD64' { 'amd64' }
        'ARM64' { 'arm64' }
        default { $arch }
    }
}

# ─── Resolve version + download URLs ─────────────────────────────────────────
if ($Version -eq 'latest') {
    Write-Step 'Resolving latest release tag'
    try {
        $resp = Invoke-WebRequest -Uri "$ReleasesBase/latest" -MaximumRedirection 0 `
            -ErrorAction Stop -UseBasicParsing
    } catch [System.Net.WebException] {
        # 302 redirect is expected; capture the Location header from the exception.
        $resp = $_.Exception.Response
    }
    $location = $null
    if ($resp -and $resp.Headers) {
        $location = if ($resp.Headers -is [System.Collections.IDictionary]) {
            $resp.Headers['Location']
        } else {
            $resp.Headers.Location
        }
    }
    if (-not $location) {
        Stop-Install "Could not resolve latest release tag from $ReleasesBase/latest"
    }
    $tag = ($location -split '/')[-1]
} else {
    $tag = if ($Version.StartsWith('v')) { $Version } else { "v$Version" }
}
$semver = $tag.TrimStart('v')
$dlBase = "$ReleasesBase/download/$tag"

$archive          = "${BinaryName}_${semver}_windows_${arch}.zip"
$archiveUrl       = "$dlBase/$archive"
$checksumsUrl     = "$dlBase/checksums.txt"
$checksumsSigUrl  = "$dlBase/checksums.txt.sig"
$checksumsCertUrl = "$dlBase/checksums.txt.pem"

Write-Step "Installing $BinaryName $tag for windows/$arch"
Write-Step "Source: $dlBase"
Write-Step "Target: $InstallDir\$Binary"

# ─── Download into a per-run temp dir ────────────────────────────────────────
$workdir = Join-Path $env:TEMP "prexorctl-install-$(Get-Random)"
New-Item -ItemType Directory -Path $workdir -Force | Out-Null

try {
    function Get-Remote ([string] $url, [string] $dest) {
        try {
            Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -ErrorAction Stop
        } catch {
            Stop-Install "Failed to download $url`: $_"
        }
    }

    Write-Step 'Downloading checksums.txt'
    Get-Remote $checksumsUrl (Join-Path $workdir 'checksums.txt')

    # ─── Cosign verification (optional) ──────────────────────────────────────
    $skipCosign = $env:PREXORCTL_COSIGN -eq 'skip'
    $cosign     = Get-Command cosign -ErrorAction SilentlyContinue

    if ($skipCosign) {
        Write-Warn 'PREXORCTL_COSIGN=skip — bypassing cosign signature verification.'
    } elseif ($cosign) {
        Write-Step 'Downloading cosign signature + certificate'
        Get-Remote $checksumsSigUrl  (Join-Path $workdir 'checksums.txt.sig')
        Get-Remote $checksumsCertUrl (Join-Path $workdir 'checksums.txt.pem')

        Write-Step 'Verifying cosign signature (Rekor transparency-log-backed)'
        & $cosign.Source verify-blob `
            --certificate                 (Join-Path $workdir 'checksums.txt.pem') `
            --signature                   (Join-Path $workdir 'checksums.txt.sig') `
            --certificate-identity-regexp $CosignIdentityRegex `
            --certificate-oidc-issuer     $CosignIssuer `
            (Join-Path $workdir 'checksums.txt')
        if ($LASTEXITCODE -ne 0) {
            Stop-Install 'cosign verification of checksums.txt failed. Set $env:PREXORCTL_COSIGN=skip only if you understand the risk.'
        }
    } else {
        Write-Warn 'cosign not installed — skipping signature verification.'
        Write-Warn 'SHA-256 catches in-flight corruption but NOT a compromised release server.'
        Write-Warn 'For production installs, install cosign: https://docs.sigstore.dev/cosign/installation/'
    }

    Write-Step "Downloading $archive"
    $archivePath = Join-Path $workdir $archive
    Get-Remote $archiveUrl $archivePath

    Write-Step 'Verifying SHA-256 against checksums.txt'
    $expectedLine = Get-Content (Join-Path $workdir 'checksums.txt') |
        Where-Object { ($_ -split '\s+', 2)[1] -eq $archive } |
        Select-Object -First 1
    if (-not $expectedLine) {
        Stop-Install "No checksum entry for $archive in checksums.txt"
    }
    $expected = ($expectedLine -split '\s+', 2)[0].ToLower()
    $actual   = (Get-FileHash -Algorithm SHA256 -Path $archivePath).Hash.ToLower()
    if ($expected -ne $actual) {
        Stop-Install "Checksum mismatch: expected $expected, got $actual"
    }

    Write-Step 'Extracting archive'
    $extractDir = Join-Path $workdir 'extracted'
    Expand-Archive -Path $archivePath -DestinationPath $extractDir -Force
    $binarySource = Join-Path $extractDir $Binary
    if (-not (Test-Path $binarySource)) {
        # GoReleaser sometimes nests; find it.
        $found = Get-ChildItem -Path $extractDir -Recurse -Filter $Binary -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $found) {
            Stop-Install "Could not find $Binary in $archive"
        }
        $binarySource = $found.FullName
    }

    # ─── Install ─────────────────────────────────────────────────────────────
    if (-not (Test-Path $InstallDir)) {
        Write-Step "Creating $InstallDir"
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    }
    $binaryDest = Join-Path $InstallDir $Binary
    Write-Step "Installing to $binaryDest"

    # If the binary is currently running (re-install case), the move will fail
    # with a sharing violation. Surface a helpful message.
    try {
        Move-Item -Path $binarySource -Destination $binaryDest -Force
    } catch [System.IO.IOException] {
        Stop-Install "Could not write $binaryDest — is prexorctl currently running? Close it and re-run."
    }

    # ─── Add to user PATH if missing ─────────────────────────────────────────
    $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
    if ($null -eq $userPath) { $userPath = '' }
    $pathParts = $userPath -split ';' | Where-Object { $_ -ne '' }
    if ($pathParts -notcontains $InstallDir) {
        Write-Step "Adding $InstallDir to user PATH"
        $newPath = if ($userPath) { "$userPath;$InstallDir" } else { $InstallDir }
        [Environment]::SetEnvironmentVariable('PATH', $newPath, 'User')
        # Update current process PATH so the version-check below works.
        $env:PATH = "$env:PATH;$InstallDir"
        Write-Warn 'PATH change takes effect in NEW shells. This window already sees it.'
    }

    # ─── Post-install verification ───────────────────────────────────────────
    Write-Step 'Verifying installation'
    $versionOutput = & $binaryDest version 2>&1
    if ($LASTEXITCODE -ne 0) {
        Stop-Install "Installed binary at $binaryDest did not run successfully:`n$versionOutput"
    }

    Write-Host ''
    Write-Host 'prexorctl installed successfully.' -ForegroundColor Green
    Write-Host $versionOutput
    Write-Host ''
    Write-Host 'Docs: https://prexor.cloud'

    # ─── Auto-launch setup wizard ────────────────────────────────────────────
    if (-not $NoSetup) {
        Write-Host ''
        Write-Step 'Launching prexorctl setup --browser'
        Write-Host '  (Pass -NoSetup to skip this step.)'
        & $binaryDest setup --browser
        exit $LASTEXITCODE
    }
} finally {
    if (Test-Path $workdir) {
        Remove-Item -Path $workdir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
