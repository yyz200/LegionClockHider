# =====================================================================
#  LegionClockHider - one-click publish to GitHub
#  (ASCII-only on purpose: runs correctly in Windows PowerShell 5.1 and 7.)
#
#  QUICK START (token resolved automatically, see below):
#     powershell -ExecutionPolicy Bypass -File publish.ps1
#
#  Token resolution order:
#     1) -Token <PAT>                 (needs "repo" scope for classic PAT)
#     2) $env:GITHUB_TOKEN / $env:GH_TOKEN
#     3) Windows Credential Manager via "git credential fill"
#        (Git Credential Manager; if nothing usable is stored it may open a
#         browser / device-code page - that is the intended one-click flow)
#
#  More control:
#     ... -RepoName MyRepo -Visibility private
#     ... -RepoUrl https://github.com/<user>/<repo>.git   # push into an existing repo
#     ... -SkipRelease                                    # repo+code only, no Release
#     ... -SkipCommit                                     # publish what is committed already
#     ... -NoInteractiveAuth                              # never trigger a browser prompt
#
#  The token is used in memory only - it is never written to .git/config or any file.
# =====================================================================
[CmdletBinding()]
param(
    [string]$RepoName = "LegionClockHider",
    [string]$RepoUrl  = "",
    [string]$Token    = "",
    [string]$UserName = "",
    [string]$UserEmail = "",
    [ValidateSet("public", "private")][string]$Visibility = "public",
    [string]$Description = "Hide the ZUXOS lockscreen clock / date / weekday (Lenovo Legion Y700 Gen5) - LSPosed module. AI-generated.",
    [string]$Version = "1.0.0",
    [switch]$SkipCommit,
    [switch]$SkipRelease,
    [switch]$NoInteractiveAuth
)

$ErrorActionPreference = "Continue"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

function Info($m) { Write-Host "[publish] $m" -ForegroundColor Cyan }
function Warn($m) { Write-Host "[publish] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[publish] $m" -ForegroundColor Red; exit 1 }

$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repo
Info ("repo dir: " + $repo)

# ---------- 0. proxy awareness ----------
$proxySet = $false
foreach ($n in @("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy")) {
    $v = [Environment]::GetEnvironmentVariable($n)
    if ($v) { $proxySet = $true; Info ("proxy detected: " + $n + "=" + $v) }
}
if (-not $proxySet) { Warn "no proxy configured - GitHub access from mainland China can be flaky; retries are built in." }

# ---------- 1. locate git ----------
function Find-Git {
    $cmd = Get-Command git -CommandType Application -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path $repo "..\tools\git\cmd\git.exe"),
        (Join-Path $repo "tools\git\cmd\git.exe"),
        "E:\deepseek harness1\tools\git\cmd\git.exe",
        "$env:ProgramFiles\Git\cmd\git.exe",
        "${env:ProgramFiles(x86)}\Git\cmd\git.exe",
        "$env:LOCALAPPDATA\Programs\Git\cmd\git.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return (Resolve-Path $c).Path } }
    return $null
}
$git = Find-Git
if (-not $git) { Fail "git not found. Install it (winget install --id Git.Git -e) or put portable MinGit in ..\tools\git\." }
Info ("git: " + $git)
$env:GIT_TERMINAL_PROMPT = "0"

# ---------- 2. init repo ----------
if (-not (Test-Path (Join-Path $repo ".git"))) {
    Info "git init (branch: main)"
    & $git init -b main | Out-Null
} else {
    Info "existing git repo detected"
}
if ($UserName)  { & $git config user.name  $UserName }
if ($UserEmail) { & $git config user.email $UserEmail }
if (-not (& $git config user.name))  { & $git config user.name  "LegionClockHider (AI-generated)" }
if (-not (& $git config user.email)) { & $git config user.email "legionclockhider@users.noreply.github.com" }
Info ("commit identity: " + (& $git config user.name) + " <" + (& $git config user.email) + ">")

# ---------- 3. commit ----------
if (-not $SkipCommit) {
    & $git add -A
    if (& $git status --porcelain) {
        $msg = "feat: LegionClockHider v$Version - AI-generated LSPosed module for ZUXOS lockscreen clock hiding"
        Info ("commit: " + $msg)
        & $git commit -q -m $msg
    } else {
        Info "nothing to commit (working tree clean)"
    }
}

# ---------- 4. GitHub API helper ----------
$script:headers = @{
    "User-Agent" = "LegionClockHider-publish"
    "Accept"     = "application/vnd.github+json"
}
function Invoke-Api {
    param(
        [string]$Uri,
        [string]$Method = "Get",
        $Body = $null,
        [string]$InFile = "",
        [string]$ContentType = "application/json; charset=utf-8",
        [int]$Retries = 3
    )
    for ($i = 1; $i -le $Retries; $i++) {
        try {
            $p = @{ Uri = $Uri; Method = $Method; Headers = $script:headers; TimeoutSec = 300 }
            if ($InFile) { $p.InFile = $InFile; $p.ContentType = $ContentType }
            elseif ($null -ne $Body) { $p.Body = $Body; $p.ContentType = $ContentType }
            return Invoke-RestMethod @p
        } catch {
            $msg = $_.Exception.Message
            $code = $null
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            if ($code -eq 401 -or $code -eq 403 -and $msg -match "rate limit") {
                throw
            }
            if ($i -eq $Retries) { throw }
            Warn ("network error (" + $msg + ") - retry " + $i + "/" + ($Retries - 1))
            if (-not $proxySet) { Warn 'hint: enable your proxy, e.g.  $env:HTTPS_PROXY="http://127.0.0.1:7890"' }
            Start-Sleep -Seconds (2 * $i)
        }
    }
}

function Get-StoredToken {
    # Ask the configured credential helper (Git Credential Manager) for github.com.
    try {
        $req = "protocol=https`nhost=github.com`n`n"
        $out = $req | & $git credential fill 2>$null
        foreach ($line in $out) {
            if ($line -like "password=*") { return ($line.Substring(9)).Trim() }
        }
    } catch { }
    return ""
}
function Drop-StoredCredential {
    try {
        $req = "protocol=https`nhost=github.com`n`n"
        $req | & $git credential reject 2>$null | Out-Null
        Info "stale credential removed from Windows Credential Manager"
    } catch { }
}
function Test-Token([string]$t) {
    if (-not $t) { return $null }
    $script:headers["Authorization"] = "token $t"
    try {
        $me = Invoke-RestMethod -Uri "https://api.github.com/user" -Headers $script:headers -TimeoutSec 30
        return $me
    } catch {
        $script:headers.Remove("Authorization") | Out-Null
        return $null
    }
}

# ---------- 5. resolve a working token ----------
$me = $null
if ($Token) {
    Info "using token from -Token"
    $me = Test-Token $Token
    if (-not $me) { Fail "the supplied token was rejected by the GitHub API (401). Check scope/expiry." }
}
if (-not $me -and ($env:GITHUB_TOKEN -or $env:GH_TOKEN)) {
    $envTok = if ($env:GITHUB_TOKEN) { $env:GITHUB_TOKEN } else { $env:GH_TOKEN }
    Info "using token from environment"
    $me = Test-Token $envTok
    if ($me) { $Token = $envTok } else { Warn "environment token rejected (401), falling through to the credential manager" }
}
if (-not $me) {
    Info "asking Windows Credential Manager (git credential fill)..."
    $stored = Get-StoredToken
    if ($stored) {
        if ($stored -eq $Token) { $stored = "" }   # same rejected token
        else {
            $me = Test-Token $stored
            if ($me) { $Token = $stored; Info "stored credential is valid" }
            else { Warn "stored credential is expired/invalid (401)" }
        }
    } else {
        Info "no stored credential found"
    }
    if (-not $me -and $stored) { Drop-StoredCredential }
    if (-not $me -and -not $NoInteractiveAuth) {
        Warn "no valid credential yet - triggering the interactive Git Credential Manager flow."
        Warn "A browser window / device-code page may open: finish the authorization there."
        $fresh = Get-StoredToken          # GCM prompts here
        if ($fresh) {
            $me = Test-Token $fresh
            if ($me) { $Token = $fresh; Info "browser authorization succeeded" }
        }
    }
}
if (-not $me) {
    Write-Host ""
    Fail @"
No usable GitHub credential.

Pick one:
  A) create a classic PAT with the "repo" scope at https://github.com/settings/tokens
     then run:  powershell -ExecutionPolicy Bypass -File publish.ps1 -Token <PAT>
  B) set it once for this session:
     `$env:GITHUB_TOKEN="<PAT>"; powershell -ExecutionPolicy Bypass -File publish.ps1
  C) commit locally only, push later from a machine where git is already signed in.
"@
}
$login = $me.login
Info ("authenticated as: " + $login)

# scope check (classic PATs expose X-OAuth-Scopes; fine-grained tokens do not)
try {
    $probe = Invoke-WebRequest -Uri "https://api.github.com/user" -Headers $script:headers -TimeoutSec 30 -UseBasicParsing
    $scopes = [string]$probe.Headers["X-OAuth-Scopes"]
    if ($scopes) {
        Info ("token scopes: " + $scopes)
        if ($scopes -notmatch "repo") { Warn "token has no 'repo' scope - creating a repository or pushing may fail." }
    }
} catch { }

# ---------- 6. create the repository if needed ----------
if (-not $RepoUrl) {
    $RepoUrl = "https://github.com/$login/$RepoName.git"
    $exists = $false
    try { Invoke-Api -Uri "https://api.github.com/repos/$login/$RepoName" | Out-Null; $exists = $true } catch { $exists = $false }
    if ($exists) {
        Info ("repository already exists: " + $RepoUrl)
    } else {
        Info ("creating repository: $login/$RepoName ($Visibility)")
        $body = @{
            name        = $RepoName
            description = $Description
            private     = ($Visibility -eq "private")
            has_issues  = $true
            has_wiki    = $false
        } | ConvertTo-Json
        try {
            Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Method Post -Headers $script:headers `
                -Body $body -ContentType "application/json" -TimeoutSec 90 | Out-Null
            Info "repository created"
        } catch {
            Fail ("repository creation failed: " + $_.Exception.Message + "  (a classic PAT needs the 'repo' scope)")
        }
    }
}
$slug = ($RepoUrl -replace "^https://github\.com/", "") -replace "\.git$", ""

# ---------- 7. push ----------
$cleanUrl = $RepoUrl -replace "https://[^@/]+@", "https://"
if ((& $git remote) -contains "origin") { & $git remote set-url origin $cleanUrl } else { & $git remote add origin $cleanUrl }
Info ("remote origin -> " + $cleanUrl)

$parts = $cleanUrl -replace "^https://", ""
$authUrl = "https://" + $login + ":" + $Token + "@" + $parts
Info "pushing (token used in memory only)"
& $git push $authUrl "HEAD:refs/heads/main" --force
$ok = ($LASTEXITCODE -eq 0)
& $git remote set-url origin $cleanUrl
if (-not $ok) { Fail "push failed - check the token scope and repository URL." }
Info "pushed"

& $git fetch origin main 2>&1 | Out-Null
& $git branch --set-upstream-to=origin/main main 2>&1 | Out-Null

# ---------- 8. release + APK asset ----------
if (-not $SkipRelease) {
    $apks = @(Get-ChildItem (Join-Path $repo "dist") -Filter *.apk -ErrorAction SilentlyContinue)
    if ($apks.Count -eq 0) {
        Warn "no APK found in dist/ - skipping release"
    } else {
        $tag = "v" + $Version
        foreach ($a in $apks) { Info ("release asset: " + $a.Name + " (" + [math]::Round($a.Length / 1KB) + " KB)") }

        $notesPath = Join-Path $repo "docs\CHANGELOG.md"
        if (Test-Path $notesPath) {
            $notes = [System.IO.File]::ReadAllText($notesPath, [System.Text.Encoding]::UTF8)
            Info "release notes: docs/CHANGELOG.md"
        } else {
            $notes = "AI-generated LSPosed module: hides the ZUXOS lockscreen clock / date / weekday."
        }

        try {
            $old = Invoke-RestMethod -Uri "https://api.github.com/repos/$slug/releases/tags/$tag" -Headers $script:headers -TimeoutSec 60
            if ($old -and $old.id) {
                Invoke-RestMethod -Uri "https://api.github.com/repos/$slug/releases/$($old.id)" -Method Delete -Headers $script:headers -TimeoutSec 60 | Out-Null
                Info ("removed previous release " + $tag)
            }
        } catch { }

        $relBody = @{
            tag_name   = $tag
            name       = "LegionClockHider $tag"
            body       = $notes
            draft      = $false
            prerelease = $false
        } | ConvertTo-Json -Depth 3
        # PS 5.1 would encode a plain string body as Latin-1 and mangle Chinese notes:
        # always send explicit UTF-8 bytes.
        $relBytes = [System.Text.Encoding]::UTF8.GetBytes($relBody)

        $rel = $null
        try {
            $rel = Invoke-Api -Uri "https://api.github.com/repos/$slug/releases" -Method Post -Body $relBytes
            Info ("release created: " + $rel.tag_name)
        } catch {
            Warn ("release creation failed: " + $_.Exception.Message)
        }

        if ($rel -and $rel.upload_url) {
            $uploadBase = $rel.upload_url -replace "\{\?name,label\}", ""
            foreach ($a in $apks) {
                $assetUrl = $uploadBase + "?name=" + [uri]::EscapeDataString($a.Name)
                try {
                    Invoke-Api -Uri $assetUrl -Method Post -InFile $a.FullName `
                        -ContentType "application/vnd.android.package-archive" | Out-Null
                    Info ("uploaded asset: " + $a.Name)
                } catch {
                    Warn ("asset upload failed (" + $a.Name + "): " + $_.Exception.Message)
                }
            }
        }
    }
}

Info "done!"
Write-Host ""
Write-Host ("  repository : https://github.com/" + $slug) -ForegroundColor Green
Write-Host ("  releases   : https://github.com/" + $slug + "/releases") -ForegroundColor Green
Write-Host ("  apk (repo) : https://github.com/" + $slug + "/blob/main/dist/LegionClockHider-v" + $Version + ".apk") -ForegroundColor Green
Write-Host ("  files      : " + ((& $git ls-files).Count) + " tracked") -ForegroundColor Green
Write-Host ""
Write-Host "  Reminder: README carries the AI-generated + not-yet-device-tested disclosure." -ForegroundColor Yellow
