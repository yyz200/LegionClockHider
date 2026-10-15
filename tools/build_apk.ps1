# =====================================================================
#  LegionClockHider - manual APK build (NO Gradle needed)
#     aapt2 -> javac -> d8 -> pack payloads -> zipalign -> apksigner
#
#  Prereqs (already present in this workspace):
#    tools\jdk17                              JDK 17
#    android-sdk\build-tools\36.0.0           aapt2 / zipalign / apksigner / d8
#    android-sdk\platforms\android-36         android.jar
#    tools\downloads\api-82.jar               legacy de.robv Xposed API (compile only)
#    tools\downloads\aar-ext\classes.jar      libxposed api 102 (compile only)
#    tools\downloads\service-ext\classes.jar  libxposed service 102 (packed into the APK)
#
#  ASCII-only comments on purpose: this file must run in Windows PowerShell 5.1.
# =====================================================================
$ErrorActionPreference = "Continue"

$root   = "E:\deepseek harness1\LegionClockHider"
$tools  = "E:\deepseek harness1\tools"
$sdk    = "E:\deepseek harness1\android-sdk"
$jdk    = "$tools\jdk17"
$build  = "$root\build"
$dist   = "$root\dist"
$appId  = "com.legion.clockhider"
$verCode = 1
$verName = "1.0.0"

$apiJar        = "$tools\downloads\api-82.jar"
$apiModernJar  = "$tools\downloads\aar-ext\classes.jar"
$apiServiceJar = "$tools\downloads\service-ext\classes.jar"

$python = "C:\Users\a\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
if (-not (Test-Path $python)) { $python = "python" }

$bt   = "$sdk\build-tools\36.0.0"
$plat = "$sdk\platforms\android-36"
if (-not (Test-Path "$bt\aapt2.exe")) { throw "build-tools missing: $bt" }
if (-not (Test-Path "$plat\android.jar")) { throw "platform missing: $plat" }
if (-not (Test-Path "$jdk\bin\javac.exe")) { throw "JDK missing: $jdk" }
foreach ($j in @($apiJar, $apiModernJar, $apiServiceJar)) {
    if (-not (Test-Path $j)) { throw "api jar missing: $j" }
}

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

New-Item -ItemType Directory -Force -Path $build | Out-Null
New-Item -ItemType Directory -Force -Path $dist  | Out-Null
if (Test-Path "$build\gen")     { Remove-Item "$build\gen" -Recurse -Force }
if (Test-Path "$build\classes") { Remove-Item "$build\classes" -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$build\gen"     | Out-Null
New-Item -ItemType Directory -Force -Path "$build\classes" | Out-Null

"== [0/9] strip UTF-8 BOM from sources (javac rejects BOM) =="
$bom = 0
foreach ($f in (Get-ChildItem "$root\app\src\main" -Recurse -File -Include *.java,*.xml,*.prop,*.list)) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        [System.IO.File]::WriteAllBytes($f.FullName, $bytes[3..($bytes.Length - 1)])
        $bom++
    }
}
"   BOM stripped: $bom"

"== [1/9] preprocess manifest (placeholder + package attr) =="
$mf = [System.IO.File]::ReadAllText("$root\app\src\main\AndroidManifest.xml", (New-Object System.Text.UTF8Encoding($false)))
$mf = $mf.Replace('${applicationId}', $appId)
$mf = $mf.Replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
                  '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="' + $appId + '">')
[System.IO.File]::WriteAllText("$build\AndroidManifest.xml", $mf, (New-Object System.Text.UTF8Encoding($false)))

"== [2/9] aapt2 compile resources =="
& "$bt\aapt2.exe" compile --dir "$root\app\src\main\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

"== [3/9] aapt2 link (assets + version) =="
& "$bt\aapt2.exe" link -o "$build\base.apk" `
    -I "$plat\android.jar" `
    --manifest "$build\AndroidManifest.xml" `
    --custom-package $appId `
    --java "$build\gen" `
    -A "$root\app\src\main\assets" `
    --min-sdk-version 27 --target-sdk-version 36 `
    --version-code $verCode --version-name $verName `
    "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

"== [4/9] javac compile (android.jar + xposed api jars + libxposed service) =="
$srcs = @(Get-ChildItem -Recurse -Filter *.java "$root\app\src\main\java" | ForEach-Object { $_.FullName })
$srcs += @(Get-ChildItem -Recurse -Filter *.java "$build\gen" | ForEach-Object { $_.FullName })
if ($srcs.Count -eq 0) { throw "no java sources found" }
"   sources: $($srcs.Count)"
$javacArgs = @('-encoding','UTF-8','-source','17','-target','17','-nowarn',
               '-classpath', "$plat\android.jar;$apiJar;$apiModernJar;$apiServiceJar",
               '-d', "$build\classes") + $srcs
& "$jdk\bin\javac.exe" @javacArgs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

"== [5/9] jar + d8 -> classes.dex (merge libxposed service) =="
& "$jdk\bin\jar.exe" cf "$build\classes.jar" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& "$bt\d8.bat" --release --lib "$plat\android.jar" --lib "$apiJar" --lib "$apiModernJar" --lib "$apiServiceJar" `
    --min-api 27 --output "$build" "$build\classes.jar" "$apiServiceJar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

"== [6/9] append classes.dex + META-INF/xposed payloads into the APK =="
& $python "$tools\pack_extra.py" "$build\base.apk" "$build\classes.dex" "$root\app\src\main\resources"
if ($LASTEXITCODE -ne 0) { throw "pack payloads failed" }

"== [7/9] zipalign =="
& "$bt\zipalign.exe" -f 4 "$build\base.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

"== [8/9] sign (release keypair; keep the keystore for future updates) =="
$ks = "$root\legionclockhider.keystore"
if (-not (Test-Path $ks)) {
    "   generating keystore: $ks"
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks `
        -storepass "legion2026" -keypass "legion2026" -alias legionclockhider `
        -dname "CN=LegionClockHider, OU=Dev, O=LegionClockHider, C=CN" `
        -keyalg RSA -keysize 2048 -validity 10950
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
$apkOut = "$dist\LegionClockHider-v$verName.apk"
if (Test-Path $apkOut) { Remove-Item $apkOut -Force }
& "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:legion2026" --key-pass "pass:legion2026" `
    --out $apkOut "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

"== [9/9] verify =="
& "$bt\apksigner.bat" verify --print-certs $apkOut | Select-Object -First 6
& "$bt\aapt2.exe" dump badging $apkOut | Select-Object -First 6

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkOut)
"== APK key entries =="
$zip.Entries | Where-Object { $_.FullName -match 'classes.dex|META-INF/xposed|assets/(xposed_init|module.prop)' } |
    ForEach-Object { "   " + $_.FullName + "  (" + $_.Length + " bytes)" }
$zip.Dispose()

"== DONE =="
"   APK : $apkOut"
"   size: " + [math]::Round((Get-Item $apkOut).Length / 1KB) + " KB"
