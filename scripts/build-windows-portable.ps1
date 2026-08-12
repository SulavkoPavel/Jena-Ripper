[CmdletBinding()]
param()

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
$buildRoot = Join-Path $releaseDir '.portable-build\windows-x64'
$packageRoot = Join-Path $buildRoot 'Jena-Ripper'
$runtimeDir = Join-Path $packageRoot 'runtime'
$pom = [xml](Get-Content -LiteralPath (Join-Path $backendDir 'pom.xml'))
$releaseVersion = [string]$pom.project.version
if ([string]::IsNullOrWhiteSpace($releaseVersion)) { throw 'Project version was not found in backend pom.xml.' }
$zipPath = Join-Path $releaseDir "Jena-Ripper-$releaseVersion-Windows-x64.zip"
$templateDir = Join-Path $projectRoot 'packaging\portable\windows'

function Resolve-CommandPath([string[]]$Names) {
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    return $null
}

function Reset-BuildDirectory([string]$Path) {
    $resolvedRelease = [IO.Path]::GetFullPath($releaseDir).TrimEnd('\') + '\'
    $resolvedTarget = [IO.Path]::GetFullPath($Path)
    if (-not $resolvedTarget.StartsWith($resolvedRelease, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a directory outside release: $resolvedTarget"
    }
    if (Test-Path -LiteralPath $resolvedTarget) { Remove-Item -LiteralPath $resolvedTarget -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $resolvedTarget | Out-Null
}

$maven = Resolve-CommandPath @('mvn.cmd', 'mvn')
if (-not $maven) {
    $ideaMaven = 'C:\Program Files\JetBrains\IntelliJ IDEA 2024.1.7\plugins\maven\lib\maven3\bin\mvn.cmd'
    if (Test-Path -LiteralPath $ideaMaven) { $maven = $ideaMaven }
}
$jlink = Resolve-CommandPath @('jlink.exe', 'jlink')
if (-not $maven) { throw 'Maven was not found.' }
if (-not $jlink) { throw 'jlink was not found. Build with the project JDK 17.' }

Push-Location $backendDir
try {
    & $maven clean package -Prelease "-Dfrontend.directory=$frontendDir"
    if ($LASTEXITCODE -ne 0) { throw 'Release JAR build failed.' }
}
finally {
    Pop-Location
}

Reset-BuildDirectory $buildRoot
New-Item -ItemType Directory -Force -Path $packageRoot | Out-Null

$modules = @(
    'java.base','java.compiler','java.datatransfer','java.desktop','java.instrument',
    'java.logging','java.management','java.management.rmi','java.naming','java.net.http',
    'java.prefs','java.rmi','java.scripting','java.security.jgss','java.security.sasl',
    'java.sql','java.sql.rowset','java.transaction.xa','java.xml','java.xml.crypto',
    'jdk.crypto.cryptoki','jdk.crypto.ec','jdk.httpserver','jdk.unsupported','jdk.zipfs'
) -join ','

& $jlink --add-modules $modules --output $runtimeDir --strip-debug --no-header-files --no-man-pages --compress=2
if ($LASTEXITCODE -ne 0) { throw 'Windows runtime build failed.' }

Copy-Item -LiteralPath (Join-Path $backendDir 'target\jena-ripper.jar') -Destination (Join-Path $packageRoot 'jena-ripper.jar')
Copy-Item -LiteralPath (Join-Path $templateDir 'start.bat') -Destination (Join-Path $packageRoot 'start.bat')
Copy-Item -LiteralPath (Join-Path $templateDir 'README.txt') -Destination (Join-Path $packageRoot 'README.txt')

if (-not (Test-Path -LiteralPath (Join-Path $runtimeDir 'bin\java.exe'))) {
    throw 'Portable runtime does not contain runtime\bin\java.exe.'
}
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -LiteralPath $packageRoot -DestinationPath $zipPath -CompressionLevel Optimal

$archive = Get-Item -LiteralPath $zipPath
Write-Host "Portable ZIP: $($archive.FullName)"
Write-Host "Size: $([math]::Round($archive.Length / 1MB, 2)) MB"
