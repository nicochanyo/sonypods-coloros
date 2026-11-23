param(
    [switch]$Rebuild,
    [switch]$SkipSourcePush,
    [switch]$SkipLspRelease,
    [switch]$SkipSourceRelease
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-Location -LiteralPath $PSScriptRoot

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "  [OK] $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "  [!] $Message" -ForegroundColor Yellow
}

$srcRepo = 'nicochanyo/sonypods-coloros'
$lspRepo = 'Xposed-Modules-Repo/io.github.nicochanyo.sonypods'

# 1. Parse versionCode and versionName from AndroidManifest.xml
Write-Step "讀取 AndroidManifest.xml 版本資訊"
$manifestPath = Join-Path $PSScriptRoot 'app\src\main\AndroidManifest.xml'
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "找不到 AndroidManifest.xml: $manifestPath"
}
$manifestContent = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8
if ($manifestContent -notmatch 'android:versionCode="(\d+)"') {
    throw "無法從 AndroidManifest.xml 解析 android:versionCode"
}
$versionCode = $Matches[1]
if ($manifestContent -notmatch 'android:versionName="([^"]+)"') {
    throw "無法從 AndroidManifest.xml 解析 android:versionName"
}
$versionName = $Matches[1]
$sourceTag = "v$versionName"
$lspTag = "$versionCode-$versionName"

Write-Ok "版本號: v$versionName (versionCode: $versionCode)"
Write-Ok "原始碼倉庫 Tag: $sourceTag | LSPosed 倉庫 Tag: $lspTag"

# 2. Ensure build.ps1 output filename matches current versionName and build APK if needed
$outDir = Join-Path $PSScriptRoot 'out'
$apkFileName = "SonyPods-ColorOS-$versionName.apk"
$apkPath = Join-Path $outDir $apkFileName
$aapt2 = Join-Path $PSScriptRoot 'tools\aapt2\aapt2.exe'

$needBuild = $Rebuild.IsPresent -or (-not (Test-Path -LiteralPath $apkPath))
if (-not $needBuild -and (Test-Path -LiteralPath $aapt2)) {
    $badging = & $aapt2 dump badging $apkPath 2>$null | Out-String
    if ($badging -notmatch "versionCode='$versionCode'" -or $badging -notmatch "versionName='$versionName'") {
        $needBuild = $true
    }
}

if ($needBuild) {
    Write-Step "編譯並簽名 APK ($apkFileName)"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "build.ps1 編譯失敗 (ExitCode: $LASTEXITCODE)"
    }
} else {
    Write-Step "檢查現有 APK ($apkFileName)"
    Write-Ok "已存在最新簽名 APK: $apkPath"
}

if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "找不到編譯後的 APK: $apkPath"
}

# 3. Extract changelog for current version from strings.xml
Write-Step "提取 v$versionName 更新日誌"
$changelogKey = "changelog_v" + ($versionName -replace '\.', '') + "_body"

function Get-StringResource([string]$XmlPath, [string]$KeyName) {
    if (-not (Test-Path -LiteralPath $XmlPath)) { return $null }
    [xml]$xml = Get-Content -LiteralPath $XmlPath -Raw -Encoding UTF8
    foreach ($node in $xml.resources.string) {
        if ($node.name -eq $KeyName) {
            return ($node.'#text' -replace '\\n', "`n").Trim()
        }
    }
    return $null
}

$notesTw = Get-StringResource (Join-Path $PSScriptRoot 'app\src\main\res\values\strings.xml') $changelogKey
$notesCn = Get-StringResource (Join-Path $PSScriptRoot 'app\src\main\res\values-zh-rCN\strings.xml') $changelogKey
$notesEn = Get-StringResource (Join-Path $PSScriptRoot 'app\src\main\res\values-en\strings.xml') $changelogKey

$releaseBodyParts = New-Object System.Collections.Generic.List[string]
if ($notesTw) {
    $releaseBodyParts.Add("### 繁體中文`n$notesTw")
}
if ($notesCn) {
    $releaseBodyParts.Add("### 简体中文`n$notesCn")
}
if ($notesEn) {
    $releaseBodyParts.Add("### English`n$notesEn")
}
if ($releaseBodyParts.Count -eq 0) {
    $releaseBodyParts.Add("Release v$versionName ($versionCode)")
}
$releaseBody = ($releaseBodyParts -join "`n`n")
Write-Ok "已載入更新日誌 ($changelogKey)"

# 4. Helper to retrieve GitHub credential from Git Credential Manager
function Get-GitHubToken {
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = 'git'
        $psi.Arguments = 'credential fill'
        $psi.RedirectStandardInput = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $proc = [System.Diagnostics.Process]::Start($psi)
        $proc.StandardInput.Write("protocol=https`nhost=github.com`n`n")
        $proc.StandardInput.Close()
        $out = $proc.StandardOutput.ReadToEnd()
        $proc.WaitForExit(5000) | Out-Null
        foreach ($line in ($out -split "`r?`n")) {
            if ($line -match '^password=(.+)$') {
                return $Matches[1].Trim()
            }
        }
    } catch {
    }
    return $null
}

$ghToken = Get-GitHubToken

# 5. Stage, verify no secrets, commit, tag, and push source code to origin
if (-not $SkipSourcePush) {
    Write-Step "推送原始碼至 GitHub ($srcRepo)"
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.git'))) {
        & git init
        & git branch -M main
    }
    $existingRemotes = (& git remote 2>$null | Out-String)
    if ($existingRemotes -notmatch '(?m)^origin$') {
        & git remote add origin "https://github.com/$srcRepo.git"
    }

    $filesToStage = @(
        'app',
        'build.ps1',
        'publish.ps1',
        '一鍵發布更新.bat',
        'README.md',
        'LICENSE',
        '.gitignore'
    )
    foreach ($item in $filesToStage) {
        $fullItem = Join-Path $PSScriptRoot $item
        if (Test-Path -LiteralPath $fullItem) {
            & git add -- $item
        }
    }

    # Security check: ensure no keystore or secret file is staged
    $stagedFiles = & git diff --cached --name-only
    foreach ($sf in $stagedFiles) {
        if ($sf -match '(\.jks$|\.keystore$|keystore\.properties$)') {
            & git reset
            throw "安全攔截：偵測到私密金鑰檔案被加入暫存區 ($sf)，已中止推送！"
        }
    }

    $hasStagedChanges = (& git diff --cached --name-only | Out-String).Trim().Length -gt 0
    if ($hasStagedChanges) {
        $commitMsg = "Release v$versionName ($versionCode)"
        & git commit -m $commitMsg
        if ($LASTEXITCODE -ne 0) {
            throw "git commit 失敗"
        }
        Write-Ok "已建立 Commit: $commitMsg"
    } else {
        Write-Ok "工作區無新增變更，略過 git commit"
    }

    & git tag -f $sourceTag
    & git push -u origin main
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "git push origin main 失敗，請確認 https://github.com/$srcRepo 是否已建立"
    } else {
        & git push origin $sourceTag --force
        Write-Ok "原始碼與 Tag ($sourceTag) 已推送至 origin"
    }
}

# 6. Helper to sync metadata files (README.md, SUMMARY, SCOPE, SOURCE_URL) to LSPosed Repository via GitHub Contents API
function Sync-GitHubFile {
    param(
        [string]$RepoSlug,
        [string]$RemotePath,
        [string]$ContentText,
        [string]$CommitMessage,
        [string]$Token
    )

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $headers = @{
        'Authorization'        = "Bearer $Token"
        'Accept'               = 'application/vnd.github+json'
        'User-Agent'           = 'sonypods-coloros-publisher'
        'X-GitHub-Api-Version' = '2022-11-28'
    }

    $existingSha = $null
    $existingContentB64 = $null
    $apiUri = "https://api.github.com/repos/$RepoSlug/contents/$RemotePath"
    try {
        $existing = Invoke-RestMethod -Uri $apiUri -Headers $headers -Method Get -ErrorAction Stop
        $existingSha = $existing.sha
        if ($existing.content) {
            $existingContentB64 = ($existing.content -replace '\s+', '')
        }
    } catch {
        $existingSha = $null
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $contentBytes = $utf8NoBom.GetBytes($ContentText)
    $newContentB64 = [Convert]::ToBase64String($contentBytes)

    if ($existingSha -and $existingContentB64 -eq $newContentB64) {
        Write-Ok "$RepoSlug/$RemotePath 已是最新，無需更新"
        return
    }

    $bodyObj = @{
        message = $CommitMessage
        content = $newContentB64
    }
    if ($existingSha) {
        $bodyObj['sha'] = $existingSha
    }

    $bodyJson = $bodyObj | ConvertTo-Json -Depth 5
    $bodyBytes = $utf8NoBom.GetBytes($bodyJson)
    Invoke-RestMethod -Uri $apiUri -Headers $headers -Method Put -Body $bodyBytes -ContentType 'application/json; charset=utf-8' | Out-Null
    Write-Ok "已同步 $RepoSlug/$RemotePath"
}

# 7. Helper to create/update a GitHub Release and upload APK asset via REST API
function Publish-GitHubRelease {
    param(
        [string]$RepoSlug,
        [string]$TagName,
        [string]$ReleaseName,
        [string]$BodyText,
        [string]$AssetPath,
        [string]$Token
    )

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $headers = @{
        'Authorization'        = "Bearer $Token"
        'Accept'               = 'application/vnd.github+json'
        'User-Agent'           = 'sonypods-coloros-publisher'
        'X-GitHub-Api-Version' = '2022-11-28'
    }

    $release = $null
    $getUri = "https://api.github.com/repos/$RepoSlug/releases/tags/$TagName"
    try {
        $release = Invoke-RestMethod -Uri $getUri -Headers $headers -Method Get -ErrorAction Stop
    } catch {
        $release = $null
    }

    $payloadObj = @{
        tag_name   = $TagName
        name       = $ReleaseName
        body       = $BodyText
        draft      = $false
        prerelease = $false
    }
    $payloadJson = $payloadObj | ConvertTo-Json -Depth 5
    $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payloadJson)

    if ($null -ne $release -and $null -ne $release.id) {
        $updateUri = "https://api.github.com/repos/$RepoSlug/releases/$($release.id)"
        $release = Invoke-RestMethod -Uri $updateUri -Headers $headers -Method Patch -Body $payloadBytes -ContentType 'application/json; charset=utf-8'
        Write-Ok "已更新 $RepoSlug 現有 Release: $TagName"
    } else {
        $createUri = "https://api.github.com/repos/$RepoSlug/releases"
        $release = Invoke-RestMethod -Uri $createUri -Headers $headers -Method Post -Body $payloadBytes -ContentType 'application/json; charset=utf-8'
        Write-Ok "已建立 $RepoSlug 新 Release: $TagName"
    }

    $assetFileName = [System.IO.Path]::GetFileName($AssetPath)
    if ($release.assets) {
        foreach ($existingAsset in $release.assets) {
            if ($existingAsset.name -eq $assetFileName) {
                $delUri = "https://api.github.com/repos/$RepoSlug/releases/assets/$($existingAsset.id)"
                Invoke-RestMethod -Uri $delUri -Headers $headers -Method Delete | Out-Null
                Write-Ok "已移除舊版同名附件: $assetFileName"
            }
        }
    }

    $encodedName = [System.Uri]::EscapeDataString($assetFileName)
    $uploadUri = "https://uploads.github.com/repos/$RepoSlug/releases/$($release.id)/assets?name=$encodedName"
    $assetResult = Invoke-RestMethod -Uri $uploadUri -Headers $headers -Method Post -InFile $AssetPath -ContentType 'application/vnd.android.package-archive'
    Write-Ok "APK 已上傳至 $RepoSlug ($($assetResult.browser_download_url))"
}

if (-not $ghToken) {
    Write-Warn "無法從 Git Credential Manager 讀取 GitHub Token，將開啟瀏覽器協助手動上傳 APK。"
}

# 8. Sync metadata files (README.md, SUMMARY, SCOPE, SOURCE_URL) & Publish Release + APK to LSPosed Repository
if (-not $SkipLspRelease) {
    if ($ghToken) {
        Write-Step "同步模組資訊檔 (README.md / SUMMARY / SCOPE / SOURCE_URL) 至 LSPosed 倉庫 ($lspRepo)"
        try {
            $readmeText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Raw -Encoding UTF8
            $summaryText = "為 ColorOS 16/17 整合 Sony 耳機原生雙耳與充電盒電量、流體雲狀態膠囊、開蓋配對彈窗、音量條降噪切換與耳機詳情頁進階控制。"
            $scopeListPath = Join-Path $PSScriptRoot 'app\src\main\resources\META-INF\xposed\scope.list'
            $scopeItems = @(Get-Content -LiteralPath $scopeListPath -Encoding UTF8 | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 })
            $scopeJson = ($scopeItems | ConvertTo-Json)
            $sourceUrlText = "https://github.com/$srcRepo`n"

            Sync-GitHubFile -RepoSlug $lspRepo -RemotePath 'README.md'  -ContentText $readmeText    -CommitMessage "Update README.md for v$versionName"  -Token $ghToken
            Sync-GitHubFile -RepoSlug $lspRepo -RemotePath 'SUMMARY'    -ContentText $summaryText   -CommitMessage "Update SUMMARY for v$versionName"    -Token $ghToken
            Sync-GitHubFile -RepoSlug $lspRepo -RemotePath 'SCOPE'      -ContentText $scopeJson     -CommitMessage "Update SCOPE for v$versionName"      -Token $ghToken
            Sync-GitHubFile -RepoSlug $lspRepo -RemotePath 'SOURCE_URL' -ContentText $sourceUrlText -CommitMessage "Update SOURCE_URL for v$versionName" -Token $ghToken
        } catch {
            Write-Warn "同步 LSPosed 倉庫資訊檔失敗 (將於發布後可手動補齊): $($_.Exception.Message)"
        }
    }

    Write-Step "發布 APK 至 LSPosed 模組倉庫 ($lspRepo)"
    $lspSuccess = $false
    if ($ghToken) {
        try {
            Publish-GitHubRelease `
                -RepoSlug $lspRepo `
                -TagName $lspTag `
                -ReleaseName "v$versionName" `
                -BodyText $releaseBody `
                -AssetPath $apkPath `
                -Token $ghToken
            $lspSuccess = $true
        } catch {
            Write-Warn "透過 API 自動發布至 $lspRepo 失敗: $($_.Exception.Message)"
        }
    }

    if (-not $lspSuccess) {
        Set-Clipboard -Value $releaseBody
        Write-Warn "已將更新日誌複製到剪貼簿，並為您開啟 LSPosed 倉庫 Release 頁面與 APK 資料夾："
        Start-Process "explorer.exe" "/select,`"$apkPath`""
        $encodedTag = [System.Uri]::EscapeDataString($lspTag)
        $encodedTitle = [System.Uri]::EscapeDataString("v$versionName")
        Start-Process "https://github.com/$lspRepo/releases/new?tag=$encodedTag&title=$encodedTitle"
    }
}

# 8. Publish Release + APK to Source Repository (nicochanyo/sonypods-coloros)
if (-not $SkipSourceRelease) {
    Write-Step "發布 APK 至原始碼倉庫 Release ($srcRepo)"
    if ($ghToken) {
        try {
            Publish-GitHubRelease `
                -RepoSlug $srcRepo `
                -TagName $sourceTag `
                -ReleaseName "v$versionName" `
                -BodyText $releaseBody `
                -AssetPath $apkPath `
                -Token $ghToken
        } catch {
            Write-Warn "透過 API 自動發布至 $srcRepo 失敗: $($_.Exception.Message)"
        }
    }
}

Write-Host ""
Write-Host "======================================================" -ForegroundColor Green
Write-Host "  v$versionName ($versionCode) 自動化發布流程執行完畢！" -ForegroundColor Green
Write-Host "  APK 路徑: $apkPath" -ForegroundColor Green
Write-Host "  原始碼倉庫: https://github.com/$srcRepo" -ForegroundColor Green
Write-Host "  LSPosed 倉庫: https://github.com/$lspRepo/releases" -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Green
