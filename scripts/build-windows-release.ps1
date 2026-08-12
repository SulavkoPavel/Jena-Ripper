[CmdletBinding()]
param(
    [ValidateSet('app-image', 'exe', 'all')]
    [string]$PackageType = 'all'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendDir = @(
    (Join-Path $projectRoot 'jena-ripper-backend'),
    (Join-Path $projectRoot 'backend')
) | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'pom.xml') } | Select-Object -First 1
$frontendDir = @(
    (Join-Path $projectRoot 'jena-ripper-frontend'),
    (Join-Path $projectRoot 'frontend')
) | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'package.json') } | Select-Object -First 1
if (-not $backendDir -or -not $frontendDir) { throw 'Backend or frontend project directory was not found.' }
$releaseDir = Join-Path $projectRoot 'release'
$jarOutputDir = Join-Path $releaseDir 'jar'
$windowsOutputDir = Join-Path $releaseDir 'windows'
$jpackageInputDir = Join-Path $backendDir 'target\jpackage-input'
$iconPath = Join-Path $projectRoot 'packaging\jena-ripper.ico'

function Resolve-CommandPath([string[]]$Names) {
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    return $null
}

$maven = Resolve-CommandPath @('mvn.cmd', 'mvn')
if (-not $maven) {
    $ideaMaven = 'C:\Program Files\JetBrains\IntelliJ IDEA 2024.1.7\plugins\maven\lib\maven3\bin\mvn.cmd'
    if (Test-Path -LiteralPath $ideaMaven) { $maven = $ideaMaven }
}
if (-not $maven) { throw 'Maven was not found. Add mvn to PATH or run the script from the IntelliJ IDEA environment.' }

$jpackage = Resolve-CommandPath @('jpackage.exe', 'jpackage')
if (-not $jpackage) { throw 'jpackage was not found. The release build requires JDK 17 with jpackage.' }
if (-not (Test-Path -LiteralPath $iconPath)) { throw "Windows icon was not found: $iconPath" }

Push-Location $backendDir
try {
    $rawVersion = (& $maven help:evaluate '-Dexpression=project.version' -q '-DforceStdout').Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($rawVersion)) {
        throw 'Could not read the version from backend/pom.xml.'
    }
    $packageVersion = ($rawVersion -replace '-SNAPSHOT$', '')
    Write-Host "Building Jena Ripper $rawVersion (package version $packageVersion)..."
    & $maven clean package -Prelease "-Dfrontend.directory=$frontendDir"
    if ($LASTEXITCODE -ne 0) { throw 'Release JAR build failed.' }
}
finally {
    Pop-Location
}

New-Item -ItemType Directory -Force -Path $jarOutputDir, $windowsOutputDir, $jpackageInputDir | Out-Null
$releaseJar = Join-Path $jarOutputDir 'jena-ripper.jar'
Copy-Item -LiteralPath (Join-Path $backendDir 'target\jena-ripper.jar') -Destination $releaseJar -Force
Copy-Item -LiteralPath $releaseJar -Destination (Join-Path $jpackageInputDir 'jena-ripper.jar') -Force

$commonArgs = @(
    '--name', 'Jena Ripper',
    '--app-version', $packageVersion,
    '--vendor', 'Jena Ripper',
    '--description', 'Apache Jena RDF graph explorer',
    '--input', $jpackageInputDir,
    '--main-jar', 'jena-ripper.jar',
    '--main-class', 'org.springframework.boot.loader.launch.JarLauncher',
    '--icon', $iconPath,
    '--java-options', '-Dspring.profiles.active=desktop',
    '--java-options', '-Dfile.encoding=UTF-8'
)

if ($PackageType -in @('app-image', 'all')) {
    $appImageRoot = Join-Path $windowsOutputDir 'app-image'
    $appImage = Join-Path $appImageRoot 'Jena Ripper'
    if (Test-Path -LiteralPath $appImage) { Remove-Item -LiteralPath $appImage -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $appImageRoot | Out-Null
    & $jpackage @commonArgs '--type' 'app-image' '--dest' $appImageRoot
    if ($LASTEXITCODE -ne 0) { throw 'jpackage app-image build failed.' }
    Write-Host "App image: $appImage"
}

if ($PackageType -in @('exe', 'all')) {
    if (-not (Resolve-CommandPath @('candle.exe')) -or -not (Resolve-CommandPath @('light.exe'))) {
        throw 'jpackage --type exe requires WiX Toolset 3.x (candle.exe and light.exe in PATH). The app-image can be built without WiX.'
    }
    & $jpackage @commonArgs '--type' 'exe' '--dest' $windowsOutputDir '--win-menu' '--win-shortcut' '--win-dir-chooser' '--win-menu-group' 'Jena Ripper'
    if ($LASTEXITCODE -ne 0) { throw 'jpackage exe build failed.' }
    $generatedInstaller = Get-ChildItem -LiteralPath $windowsOutputDir -Filter '*.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $generatedInstaller) { throw 'jpackage completed without producing an installer exe.' }
    $installerPath = Join-Path $windowsOutputDir "Jena-Ripper-$packageVersion.exe"
    if ($generatedInstaller.FullName -ne $installerPath) {
        Move-Item -LiteralPath $generatedInstaller.FullName -Destination $installerPath -Force
    }
    Write-Host "Installer: $installerPath"
}

Write-Host "Release JAR: $releaseJar"
