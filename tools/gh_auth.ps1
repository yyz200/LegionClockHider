# =====================================================================
#  Trigger the interactive Git Credential Manager authorization for github.com.
#
#  Why a separate script: the token must never reach the console/transcript.
#  This one prints ONLY the username, the token length, and the API validation
#  result - never the token itself.
#
#  Usage:  powershell -ExecutionPolicy Bypass -File tools/gh_auth.ps1
# =====================================================================
$ErrorActionPreference = "Continue"
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

function Find-Git {
    $cmd = Get-Command git -CommandType Application -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($c in @("E:\deepseek harness1\tools\git\cmd\git.exe",
                     "$env:ProgramFiles\Git\cmd\git.exe")) {
        if (Test-Path $c) { return $c }
    }
    return "git"
}
$git = Find-Git
Write-Output ("git: " + $git)

# Force the helper to actually prompt instead of silently failing.
$env:GCM_INTERACTIVE   = "always"
$env:GIT_TERMINAL_PROMPT = "1"

Write-Output "step 1/3: removing the expired credential from Windows Credential Manager ..."
$req = "protocol=https`nhost=github.com`n`n"
$req | & $git credential reject 2>$null | Out-Null

Write-Output "step 2/3: asking Git Credential Manager to authenticate (a window/browser should open) ..."
$out = $req | & $git -c credential.interactive=true credential fill 2>&1
$user = ""
$pass = ""
foreach ($line in $out) {
    if ($line -like "username=*") { $user = ($line.Substring(9)).Trim() }
    if ($line -like "password=*") { $pass = ($line.Substring(9)).Trim() }
    if ($line -match "^(info|error|fatal|warning)") { Write-Output ("   helper: " + $line) }
}
Write-Output ("   username    = " + $(if ($user) { $user } else { "(none)" }))
Write-Output ("   tokenLength = " + $pass.Length)

if (-not $pass) {
    Write-Output "RESULT=NO_TOKEN"
    exit 1
}

Write-Output "step 3/3: validating the token against the GitHub API ..."
$h = @{ Authorization = "token $pass"; "User-Agent" = "lch-auth"; Accept = "application/vnd.github+json" }
try {
    $resp = Invoke-WebRequest -Uri "https://api.github.com/user" -Headers $h -TimeoutSec 30 -UseBasicParsing
    $me = $resp.Content | ConvertFrom-Json
    $scopes = [string]$resp.Headers["X-OAuth-Scopes"]
    Write-Output ("   login  = " + $me.login)
    Write-Output ("   scopes = " + $(if ($scopes) { $scopes } else { "(fine-grained token: scopes not reported)" }))
    Write-Output "RESULT=OK"
} catch {
    $code = $null
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    Write-Output ("   API rejected the token (HTTP " + $code + ")")
    Write-Output "RESULT=API_REJECTED"
    exit 2
}
