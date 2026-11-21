param(
    [ValidateSet("check", "create-tag", "verify-release")]
    [string]$Action = "check",
    [string]$Tag = "",
    [string]$RepoRoot = "",
    [switch]$CheckRemote,
    [switch]$RequireJar,
    [switch]$RunBuild
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $RepoRoot = Split-Path -Parent $ScriptDir
}

$VersionPropsPath = Join-Path $RepoRoot "src\main\resources\version.properties"
if (-not (Test-Path $VersionPropsPath)) {
    throw "Missing version.properties at $VersionPropsPath"
}

$Props = @{}
foreach ($rawLine in (Get-Content -Path $VersionPropsPath -Encoding UTF8)) {
    $line = $rawLine.Trim()
    if ([string]::IsNullOrEmpty($line) -or $line.StartsWith("#")) {
        continue
    }
    $eqIdx = $line.IndexOf("=")
    if ($eqIdx -gt 0) {
        $k = $line.Substring(0, $eqIdx).Trim()
        $v = $line.Substring($eqIdx + 1).Trim()
        $Props[$k] = $v
    }
}

$Version = $Props["version"]
$Major = $Props["major"]
$Minor = $Props["minor"]
$Channel = $Props["channel"]

if ([string]::IsNullOrWhiteSpace($Version)) {
    throw "version.properties is missing 'version='"
}
if ($Version -notmatch '^V_(\d+)\.(\d+)(?:_([A-Za-z0-9._-]+))?$') {
    throw "Invalid version format '$Version'. Expected V_<major>.<minor>[_<channel>]."
}
if ($Major -and $Major -ne $Matches[1]) {
    throw "version.properties mismatch: major=$Major vs version='$Version'"
}
if ($Minor -and $Minor -ne $Matches[2]) {
    throw "version.properties mismatch: minor=$Minor vs version='$Version'"
}
if ($Channel -and $Matches[3] -and ($Channel -ne $Matches[3])) {
    throw "version.properties mismatch: channel=$Channel vs version='$Version'"
}

$TargetTag = if ([string]::IsNullOrWhiteSpace($Tag)) { $Version } else { $Tag.Trim() }
if ([string]::IsNullOrWhiteSpace($TargetTag)) {
    throw "Target release tag must not be empty."
}

if ($RunBuild -or $Action -eq "verify-release") {
    $BuildScript = Join-Path $RepoRoot "build.ps1"
    if (-not (Test-Path $BuildScript)) {
        throw "Missing build.ps1 at $BuildScript"
    }
    & powershell -ExecutionPolicy Bypass -File $BuildScript
    if ($LASTEXITCODE -ne 0) {
        throw "build.ps1 failed with exit code $LASTEXITCODE"
    }
}

$JarPath = Join-Path $RepoRoot "dist\theMythOfCrawl-crawler.jar"
if (($RequireJar -or $RunBuild -or $Action -eq "verify-release") -and (-not (Test-Path $JarPath))) {
    throw "Required extension artifact missing at $JarPath. Run .\build.ps1 first."
}

# Check local git tags (refuse overwrite or reuse of an immutable tag)
$LocalTag = (& git -C $RepoRoot tag -l $TargetTag 2>$null | Out-String).Trim()
if (-not [string]::IsNullOrWhiteSpace($LocalTag)) {
    throw "IMMUTABLE RELEASE GUARD: Local git tag '$TargetTag' already exists. Refusing to overwrite or delete; bump version.properties before releasing."
}

if ($CheckRemote) {
    $RemoteOut = & git -C $RepoRoot ls-remote --tags origin "refs/tags/$TargetTag" 2>&1
    if ($LASTEXITCODE -eq 0 -and (-not [string]::IsNullOrWhiteSpace(($RemoteOut | Out-String).Trim()))) {
        throw "IMMUTABLE RELEASE GUARD: Remote git tag '$TargetTag' already exists on origin. Refusing to overwrite or delete."
    }
}

if ($Action -eq "create-tag") {
    $Status = (& git -C $RepoRoot status --porcelain 2>$null | Out-String).Trim()
    if (-not [string]::IsNullOrWhiteSpace($Status)) {
        throw "IMMUTABLE RELEASE GUARD: Working tree has uncommitted changes; commit cleanly before tagging '$TargetTag'."
    }
    if (-not (Test-Path $JarPath)) {
        throw "IMMUTABLE RELEASE GUARD: Missing built JAR at $JarPath; run .\build.ps1 before creating tag '$TargetTag'."
    }
    & git -C $RepoRoot tag -a $TargetTag -m "Immutable release $TargetTag"
    if ($LASTEXITCODE -ne 0) {
        throw "git tag -a $TargetTag failed with exit code $LASTEXITCODE"
    }
    Write-Host "CREATED IMMUTABLE TAG: $TargetTag"
} else {
    Write-Host "RELEASE GUARD OK: version=$Version targetTag=$TargetTag (not yet tagged, safe for next immutable release)"
}
