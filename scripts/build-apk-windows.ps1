$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Sdk = Join-Path $Root '.android-sdk'
$ToolsZip = Join-Path $Root '.android-commandlinetools.zip'
$ToolsDir = Join-Path $Sdk 'cmdline-tools\latest'
$SdkManager = Join-Path $ToolsDir 'bin\sdkmanager.bat'

Write-Host 'DK Alarm - automatic APK builder' -ForegroundColor Cyan

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host 'Java was not found. Installing Temurin JDK 17 with winget...'
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw 'Java 17 is required and winget is unavailable. Install Temurin/OpenJDK 17 and run this file again.'
    }
    winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-package-agreements --accept-source-agreements
    $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
}

if (-not (Test-Path $SdkManager)) {
    New-Item -ItemType Directory -Force -Path $Sdk | Out-Null
    $url = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
    Write-Host 'Downloading Android command-line tools...'
    Invoke-WebRequest -Uri $url -OutFile $ToolsZip
    $tmp = Join-Path $Root '.android-tools-tmp'
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive $ToolsZip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
    Copy-Item (Join-Path $tmp 'cmdline-tools\*') $ToolsDir -Recurse -Force
    Remove-Item $tmp -Recurse -Force
}

$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_HOME = $Sdk

Write-Host 'Accepting Android SDK licenses...'
1..20 | ForEach-Object { 'y' } | & $SdkManager --sdk_root=$Sdk --licenses | Out-Host
Write-Host 'Installing Android SDK 36...'
& $SdkManager --sdk_root=$Sdk 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'

Write-Host 'Building APK...'
Push-Location $Root
try {
    & .\gradlew.bat --no-daemon clean assembleDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }

$apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw 'Build finished but APK was not found.' }
$dest = Join-Path $Root 'DKAlarm-text-debug.apk'
Copy-Item $apk $dest -Force
Write-Host "DONE: $dest" -ForegroundColor Green
Start-Process explorer.exe "/select,`"$dest`""
