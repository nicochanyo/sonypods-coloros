$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Invoke-Checked {
    param([string]$Label, [scriptblock]$Cmd)
    Write-Host "==> $Label"
    & $Cmd
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

$aapt2 = Join-Path $PSScriptRoot 'tools\aapt2\aapt2.exe'
$r8Jar = Join-Path $PSScriptRoot 'tools\r8.jar'
$androidJar = Join-Path $PSScriptRoot 'tools\android-35.jar'
$signerJar = Join-Path $PSScriptRoot 'tools\uber-apk-signer-1.3.0.jar'
$buildDir = Join-Path $PSScriptRoot 'build'
$outDir = Join-Path $PSScriptRoot 'out'
$manifestXml = Get-Content (Join-Path $PSScriptRoot 'app\src\main\AndroidManifest.xml') -Raw -Encoding UTF8
$verName = if ($manifestXml -match 'android:versionName="([^"]+)"') { $Matches[1] } else { '1.0.0' }
$finalApk = Join-Path $outDir "SonyPods-ColorOS-$verName.apk"

# === 簽名設定（優先讀取 keystore.properties 或環境變數；無私鑰時改用 Debug 簽章）===
$ksProps = @{}
$ksPropsPath = Join-Path $PSScriptRoot 'keystore.properties'
if (Test-Path $ksPropsPath) {
    foreach ($rawLine in Get-Content $ksPropsPath) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $parts = $line.Split('=', 2)
        if ($parts.Count -eq 2) {
            $ksProps[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
}
$ksFileRaw = if ($env:KS_FILE) { $env:KS_FILE } elseif ($ksProps['storeFile']) { $ksProps['storeFile'] } else { 'release.jks' }
$ksFile = if ([System.IO.Path]::IsPathRooted($ksFileRaw)) { $ksFileRaw } else { Join-Path $PSScriptRoot $ksFileRaw }
$ksAlias = if ($env:KS_ALIAS) { $env:KS_ALIAS } else { $ksProps['keyAlias'] }
$ksPass = if ($env:KS_PASS) { $env:KS_PASS } else { $ksProps['storePassword'] }
$ksKeyPass = if ($env:KS_KEY_PASS) { $env:KS_KEY_PASS } elseif ($ksProps['keyPassword']) { $ksProps['keyPassword'] } else { $ksPass }
$useReleaseSign = (Test-Path $ksFile) -and $ksAlias -and $ksPass
# ============================================================================

if (Test-Path $buildDir) {
    Remove-Item -Recurse -Force $buildDir
}
New-Item -ItemType Directory -Force -Path `
    (Join-Path $buildDir 'stubs'), `
    (Join-Path $buildDir 'gen'), `
    (Join-Path $buildDir 'classes'), `
    (Join-Path $buildDir 'dex'), `
    (Join-Path $buildDir 'signed'), `
    $outDir | Out-Null

# 1. Compile compile-only Xposed stubs
$stubSources = @(Get-ChildItem -Path (Join-Path $PSScriptRoot 'compile-stubs') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
Invoke-Checked 'Compile Xposed stubs' {
    & javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -cp $androidJar -d (Join-Path $buildDir 'stubs') $stubSources
}

# 2. Compile Android resources with AAPT2
$resZip = Join-Path $buildDir 'res.zip'
Invoke-Checked 'AAPT2 compile resources' {
    & $aapt2 compile --dir (Join-Path $PSScriptRoot 'app\src\main\res') -o $resZip
}

# 3. Link resources, manifest, and generate R.java
$unsignedApk = Join-Path $buildDir 'unsigned.apk'
Invoke-Checked 'AAPT2 link' {
    & $aapt2 link `
        -I $androidJar `
        --manifest (Join-Path $PSScriptRoot 'app\src\main\AndroidManifest.xml') `
        --java (Join-Path $buildDir 'gen') `
        --auto-add-overlay `
        -o $unsignedApk `
        $resZip
}

# 4. Compile app Java sources + R.java (excluding stubs from output)
$appSources = @()
$appSources += @(Get-ChildItem -Path (Join-Path $PSScriptRoot 'app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$appSources += @(Get-ChildItem -Path (Join-Path $buildDir 'gen') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$cp = "$androidJar;" + (Join-Path $buildDir 'stubs')
Invoke-Checked 'Compile Java sources' {
    & javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -cp $cp -d (Join-Path $buildDir 'classes') $appSources
}

# 5. Convert ONLY build\classes to DEX using D8
$classFiles = @(Get-ChildItem -Path (Join-Path $buildDir 'classes') -Recurse -Filter '*.class' | ForEach-Object { $_.FullName })
Invoke-Checked 'D8 dex compilation' {
    & java -cp $r8Jar com.android.tools.r8.D8 `
        --release `
        --min-api 26 `
        --lib $androidJar `
        --output (Join-Path $buildDir 'dex') `
        $classFiles
}

# 6. Insert classes.dex & META-INF/xposed/* into unsigned.apk while preserving resources.arsc as ZIP_STORED
$dexFile = Join-Path $buildDir 'dex\classes.dex'
$resMetaDir = Join-Path $PSScriptRoot 'app\src\main\resources\META-INF\xposed'
$bundledCatalog = Join-Path $PSScriptRoot 'app\src\main\resources\sony_official_catalog.json'
Invoke-Checked 'Package classes.dex and API 102 metadata into APK' {
    @"
import zipfile, os

apk_in = r'$unsignedApk'
apk_tmp = apk_in + '.tmp'
dex_path = r'$dexFile'
xposed_dir = r'$resMetaDir'
catalog_path = r'$bundledCatalog'

with zipfile.ZipFile(apk_in, 'r') as zin, zipfile.ZipFile(apk_tmp, 'w') as zout:
    for info in zin.infolist():
        if info.filename in ('classes.dex', 'assets/xposed_init', 'sony_official_catalog.json') or info.filename.startswith('META-INF/xposed/'):
            continue
        data = zin.read(info.filename)
        ct = zipfile.ZIP_STORED if info.filename == 'resources.arsc' else info.compress_type
        zinfo = zipfile.ZipInfo(info.filename)
        zinfo.compress_type = ct
        zout.writestr(zinfo, data, compress_type=ct)
    with open(dex_path, 'rb') as df:
        zout.writestr('classes.dex', df.read(), compress_type=zipfile.ZIP_STORED)
    if os.path.isfile(catalog_path):
        with open(catalog_path, 'rb') as cf:
            zout.writestr('sony_official_catalog.json', cf.read(), compress_type=zipfile.ZIP_DEFLATED)
    for fn in sorted(os.listdir(xposed_dir)):
        fp = os.path.join(xposed_dir, fn)
        if os.path.isfile(fp):
            with open(fp, 'rb') as xf:
                zout.writestr('META-INF/xposed/' + fn, xf.read(), compress_type=zipfile.ZIP_DEFLATED)

os.replace(apk_tmp, apk_in)
"@ | & python -
}

# 7. Zipalign & Sign with uber-apk-signer
if ($useReleaseSign) {
    Invoke-Checked 'Sign & align APK (Release Keystore)' {
        & java -jar $signerJar `
            -a $unsignedApk `
            -o (Join-Path $buildDir 'signed') `
            --ks $ksFile `
            --ksAlias $ksAlias `
            --ksPass $ksPass `
            --ksKeyPass $ksKeyPass
    }
} else {
    Write-Warning "未找到 keystore.properties 或 release.jks，將使用 Debug 簽章進行本地測試編譯。"
    Invoke-Checked 'Sign & align APK (Debug Fallback)' {
        & java -jar $signerJar `
            -a $unsignedApk `
            -o (Join-Path $buildDir 'signed')
    }
}

$signedApk = Get-ChildItem -Path (Join-Path $buildDir 'signed') -Filter '*.apk' | Select-Object -First 1
if (-not $signedApk) {
    throw 'No signed APK found in build\signed'
}
Copy-Item -Force $signedApk.FullName $finalApk

if ($useReleaseSign) {
    Invoke-Checked 'Verify APK signature (SHA256)' {
        & java -jar $signerJar `
            --onlyVerify `
            -a $finalApk `
            --verifySha256 14c97c70daa1ff68aef9af5169d090a526cf6edb9bc6cd66c75f72d4b7fdd3f9
    }
}

Write-Host "Build succeeded: $finalApk"
