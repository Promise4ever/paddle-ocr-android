$ErrorActionPreference = 'Stop'

$jdkHome = 'D:\AndroidDev\jdk-home\jdk-17.0.20+8'
$sdkRoot = 'D:\AndroidDev\sdk'
$env:JAVA_HOME = $jdkHome

New-Item -ItemType Directory -Force -Path $sdkRoot | Out-Null

# 1. Download Android commandline tools
$zip = 'D:\AndroidDev\cmdline-tools.zip'
if (-not (Test-Path $zip)) {
    Write-Output 'Downloading Android cmdline-tools...'
    & curl.exe -L --retry 3 --max-time 900 -o $zip 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
}
$size = (Get-Item $zip).Length
Write-Output ("cmdline-tools zip size: {0:N1} MB" -f ($size / 1MB))
if ($size -lt 50MB) { Write-Output 'Download too small, aborting.'; exit 1 }

# 2. Extract to sdk\cmdline-tools\latest
$tmp = 'D:\AndroidDev\cmdline-tools-tmp'
if (Test-Path $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
Expand-Archive -LiteralPath $zip -DestinationPath $tmp -Force
$latest = Join-Path $sdkRoot 'cmdline-tools\latest'
New-Item -ItemType Directory -Force -Path $latest | Out-Null
Copy-Item -Path (Join-Path $tmp 'cmdline-tools\*') -Destination $latest -Recurse -Force
Remove-Item -LiteralPath $tmp -Recurse -Force

$sdk = Join-Path $latest 'bin\sdkmanager.bat'

# 3. Accept licenses
Write-Output 'Accepting licenses...'
1..40 | ForEach-Object { 'y' } | & $sdk ('--sdk_root=' + $sdkRoot) '--licenses' 2>&1 | Select-Object -Last 3

$licDir = Join-Path $sdkRoot 'licenses'
if (-not (Test-Path (Join-Path $licDir 'android-sdk-license'))) {
    Write-Output 'Writing license files manually (fallback)...'
    New-Item -ItemType Directory -Force -Path $licDir | Out-Null
    @(
        'android-sdk-license:',
        '24333f8a63b6825ea9c5514f83c2829b004d1fee',
        '8933bad161af4178b1185d1a37fbf41ea5269c55',
        'd56f5187479451eabf01fb78af6dfcb131a6481e',
        '',
        'android-sdk-preview-license:',
        '84831b9409646a918e30573bab4c9c91346d8abd',
        ''
    ) | Set-Content -LiteralPath (Join-Path $licDir 'android-sdk-license')
}

# 4. Install packages
Write-Output 'Installing platform-tools, platforms;android-35, build-tools;35.0.0 ...'
& $sdk ('--sdk_root=' + $sdkRoot) 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 2>&1 | Select-Object -Last 6

# 5. Verify
Write-Output '--- VERIFY ---'
Get-ChildItem (Join-Path $sdkRoot 'platforms') -Directory | Select-Object Name
Get-ChildItem (Join-Path $sdkRoot 'build-tools') -Directory | Select-Object Name
if (-not (Test-Path (Join-Path $sdkRoot 'platform-tools\adb.exe'))) { Write-Output 'adb.exe missing!'; exit 1 }
Write-Output 'adb.exe OK'

# 6. Configure user environment variables
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkHome, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdkRoot, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $sdkRoot, 'User')
[Environment]::SetEnvironmentVariable('GRADLE_USER_HOME', 'D:\AndroidDev\gradle-home', 'User')
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$adds = @(
    (Join-Path $jdkHome 'bin'),
    (Join-Path $sdkRoot 'platform-tools'),
    (Join-Path $sdkRoot 'cmdline-tools\latest\bin')
)
foreach ($a in $adds) {
    if ($userPath -notlike "*$a*") {
        $userPath = $userPath.TrimEnd(';') + ';' + $a
    }
}
[Environment]::SetEnvironmentVariable('Path', $userPath, 'User')
Write-Output 'User environment variables configured.'
Write-Output 'DONE'
