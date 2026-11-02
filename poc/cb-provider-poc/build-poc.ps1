# Builds the PoC with plain SDK tools so it depends on nothing but build-tools and android.jar.
param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$BuildTools = "34.0.0",
    [string]$Platform = "android-34"
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$out = Join-Path $root "build"
New-Item -ItemType Directory -Force -Path "$out\classes", "$out\dex" | Out-Null

$bt = Join-Path $Sdk "build-tools\$BuildTools"
$androidJar = Join-Path $Sdk "platforms\$Platform\android.jar"

# The launcher on PATH may only expose java/javac, so locate the real JDK for keytool.
$javaHome = ((& cmd /c "java -XshowSettings:properties -version 2>&1") |
    Select-String -Pattern '^\s*java\.home\s*=').ToString().Split('=', 2)[1].Trim()
$keytool = Join-Path $javaHome "bin\keytool.exe"

$sources = Get-ChildItem -Path "$root\src" -Filter *.java -Recurse | ForEach-Object { $_.FullName }
& javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath $androidJar -classpath $androidJar -d "$out\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classes = Get-ChildItem -Path "$out\classes" -Filter *.class -Recurse | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 30 --lib $androidJar --output "$out\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

& "$bt\aapt2.exe" link -o "$out\unaligned.apk" -I $androidJar --manifest "$root\AndroidManifest.xml"
if ($LASTEXITCODE -ne 0) { throw "aapt2 failed" }

Push-Location "$out\dex"
try {
    & "$bt\aapt.exe" add -f "$out\unaligned.apk" classes.dex | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "aapt add failed" }
} finally {
    Pop-Location
}

& "$bt\zipalign.exe" -f 4 "$out\unaligned.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$keystore = Join-Path $out "poc.keystore"
if (-not (Test-Path $keystore)) {
    & $keytool -genkeypair -keystore $keystore -storepass pocpoc -keypass pocpoc -alias poc `
        -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=CellAlertHook PoC" 2>&1 | Out-Null
}
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:pocpoc --key-pass pass:pocpoc --out "$out\cb-provider-poc.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Output "built $out\cb-provider-poc.apk"
