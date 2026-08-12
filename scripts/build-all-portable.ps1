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
$releaseDir = Join-Path $projectRoot 'release'
$workDir = Join-Path $releaseDir 'work'
$runtimeCache = Join-Path $projectRoot 'packaging\runtime-cache'
$portableTemplates = Join-Path $projectRoot 'packaging\portable'
$stage = 'Initialization'
$pom = [xml](Get-Content -LiteralPath (Join-Path $backendDir 'pom.xml'))
$releaseVersion = [string]$pom.project.version
if ([string]::IsNullOrWhiteSpace($releaseVersion)) { throw 'Project version was not found in backend pom.xml.' }

$platforms = @(
    [pscustomobject]@{ Id = 'windows-x64'; Label = 'Windows x64'; Archive = "Jena-Ripper-$releaseVersion-Windows-x64.zip"; Java = 'bin\java.exe'; Template = 'windows' },
    [pscustomobject]@{ Id = 'macos-arm64'; Label = 'macOS Apple Silicon'; Archive = "Jena-Ripper-$releaseVersion-macOS-arm64.zip"; Java = 'bin\java'; Template = 'macos' },
    [pscustomobject]@{ Id = 'macos-x64'; Label = 'macOS Intel'; Archive = "Jena-Ripper-$releaseVersion-macOS-x64.zip"; Java = 'bin\java'; Template = 'macos' }
)

function Resolve-CommandPath([string[]]$Names) {
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    return $null
}

function Assert-PathInside([string]$Child, [string]$Parent) {
    $resolvedChild = [IO.Path]::GetFullPath($Child)
    $resolvedParent = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedChild.StartsWith($resolvedParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside ${Parent}: $resolvedChild"
    }
}

function Set-ZipEntryMode($Entry, [int]$Mode) {
    $unsigned = [uint32]($Mode * 65536)
    $Entry.ExternalAttributes = [BitConverter]::ToInt32([BitConverter]::GetBytes($unsigned), 0)
}

function Set-UnixZipHost([string]$ZipPath) {
    $bytes = [IO.File]::ReadAllBytes($ZipPath)
    $eocd = -1
    for ($index = $bytes.Length - 22; $index -ge [Math]::Max(0, $bytes.Length - 65557); $index--) {
        if ($bytes[$index] -eq 0x50 -and $bytes[$index + 1] -eq 0x4b -and $bytes[$index + 2] -eq 0x05 -and $bytes[$index + 3] -eq 0x06) {
            $eocd = $index
            break
        }
    }
    if ($eocd -lt 0) { throw "ZIP end-of-central-directory was not found: $ZipPath" }
    $entryCount = [BitConverter]::ToUInt16($bytes, $eocd + 10)
    $centralOffset = [BitConverter]::ToUInt32($bytes, $eocd + 16)
    $cursor = [int64]$centralOffset
    for ($entryIndex = 0; $entryIndex -lt $entryCount; $entryIndex++) {
        if ($bytes[$cursor] -ne 0x50 -or $bytes[$cursor + 1] -ne 0x4b -or $bytes[$cursor + 2] -ne 0x01 -or $bytes[$cursor + 3] -ne 0x02) {
            throw "Invalid ZIP central directory: $ZipPath"
        }
        $bytes[$cursor + 5] = 3
        $nameLength = [BitConverter]::ToUInt16($bytes, $cursor + 28)
        $extraLength = [BitConverter]::ToUInt16($bytes, $cursor + 30)
        $commentLength = [BitConverter]::ToUInt16($bytes, $cursor + 32)
        $cursor += 46 + $nameLength + $extraLength + $commentLength
    }
    [IO.File]::WriteAllBytes($ZipPath, $bytes)
}

function New-UnixZip([string]$SourceDirectory, [string]$DestinationPath) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path -LiteralPath $DestinationPath) { Remove-Item -LiteralPath $DestinationPath -Force }
    $stream = [IO.File]::Open($DestinationPath, [IO.FileMode]::CreateNew)
    try {
        $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $false, [Text.Encoding]::UTF8)
        try {
            $rootName = Split-Path -Leaf $SourceDirectory
            $rootEntry = $archive.CreateEntry("$rootName/")
            Set-ZipEntryMode $rootEntry 0x41ed
            foreach ($item in Get-ChildItem -LiteralPath $SourceDirectory -Force -Recurse) {
                $relative = $item.FullName.Substring($SourceDirectory.Length).TrimStart('\', '/').Replace('\', '/')
                $entryName = "$rootName/$relative"
                if ($item.PSIsContainer) {
                    $entry = $archive.CreateEntry($entryName.TrimEnd('/') + '/')
                    Set-ZipEntryMode $entry 0x41ed
                    continue
                }
                $entry = $archive.CreateEntry($entryName, [IO.Compression.CompressionLevel]::Optimal)
                $executable = $entryName.EndsWith('/Jena Ripper.command', [StringComparison]::Ordinal) `
                    -or $entryName -match '/runtime/bin/[^/]+$' `
                    -or $entryName.EndsWith('/runtime/lib/jspawnhelper', [StringComparison]::Ordinal)
                Set-ZipEntryMode $entry $(if ($executable) { 0x81ed } else { 0x81a4 })
                $input = [IO.File]::OpenRead($item.FullName)
                try {
                    $output = $entry.Open()
                    try { $input.CopyTo($output) } finally { $output.Dispose() }
                } finally { $input.Dispose() }
            }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
    Set-UnixZipHost $DestinationPath
}

function Assert-MacLauncherExecutable([string]$ZipPath) {
    $bytes = [IO.File]::ReadAllBytes($ZipPath)
    $eocd = -1
    for ($index = $bytes.Length - 22; $index -ge [Math]::Max(0, $bytes.Length - 65557); $index--) {
        if ($bytes[$index] -eq 0x50 -and $bytes[$index + 1] -eq 0x4b -and $bytes[$index + 2] -eq 0x05 -and $bytes[$index + 3] -eq 0x06) { $eocd = $index; break }
    }
    if ($eocd -lt 0) { throw "Cannot verify ZIP metadata: $ZipPath" }
    $entryCount = [BitConverter]::ToUInt16($bytes, $eocd + 10)
    $cursor = [int64][BitConverter]::ToUInt32($bytes, $eocd + 16)
    for ($entryIndex = 0; $entryIndex -lt $entryCount; $entryIndex++) {
        $creatorHost = $bytes[$cursor + 5]
        $nameLength = [BitConverter]::ToUInt16($bytes, $cursor + 28)
        $extraLength = [BitConverter]::ToUInt16($bytes, $cursor + 30)
        $commentLength = [BitConverter]::ToUInt16($bytes, $cursor + 32)
        $name = [Text.Encoding]::UTF8.GetString($bytes, [int]$cursor + 46, $nameLength)
        if ($name.EndsWith('/Jena Ripper.command', [StringComparison]::Ordinal)) {
            $attributes = [BitConverter]::ToUInt32($bytes, $cursor + 38)
            $mode = ($attributes -shr 16) -band 0xffff
            if ($creatorHost -ne 3 -or ($mode -band 0x49) -eq 0) {
                throw "macOS launcher is not stored as a Unix executable in $ZipPath (host=$creatorHost, mode=$([Convert]::ToString($mode, 8)))."
            }
            return
        }
        $cursor += 46 + $nameLength + $extraLength + $commentLength
    }
    throw "Jena Ripper.command was not found in $ZipPath"
}

try {
    Write-Host '========================================'
    Write-Host 'Jena Ripper Portable Release'
    Write-Host '========================================'
    Write-Host

    if (-not $backendDir -or -not $frontendDir) { throw 'Backend or frontend project directory was not found.' }
    $stage = 'Cleaning generated release files'
    Assert-PathInside $releaseDir $projectRoot
    if (Test-Path -LiteralPath $releaseDir) { Remove-Item -LiteralPath $releaseDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $releaseDir, $workDir | Out-Null

    foreach ($platform in $platforms) {
        $stage = "$($platform.Label) runtime missing"
        $runtime = Join-Path $runtimeCache $platform.Id
        $java = Join-Path $runtime $platform.Java
        if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
            throw "Runtime not found: $($platform.Label)`nExpected: $runtime"
        }
    }

    $stage = 'Frontend build'
    $npm = Resolve-CommandPath @('npm.cmd', 'npm')
    if (-not $npm) { throw 'npm was not found.' }
    $maven = Resolve-CommandPath @('mvn.cmd', 'mvn')
    if (-not $maven) {
        $ideaMaven = 'C:\Program Files\JetBrains\IntelliJ IDEA 2024.1.7\plugins\maven\lib\maven3\bin\mvn.cmd'
        if (Test-Path -LiteralPath $ideaMaven) { $maven = $ideaMaven }
    }
    if (-not $maven) { throw 'Maven was not found.' }

    Push-Location $frontendDir
    try {
        if (-not (Test-Path -LiteralPath (Join-Path $frontendDir 'node_modules'))) {
            & $npm ci
            if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
        } else {
            Write-Host '[OK] Existing frontend dependencies'
        }
        & $npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend production build failed.' }
    } finally { Pop-Location }
    Write-Host '[OK] Frontend build'

    $stage = 'Backend release JAR'
    Push-Location $backendDir
    try {
        & $maven clean package -Prelease "-Dexec.skip=true" "-Dfrontend.directory=$frontendDir"
        if ($LASTEXITCODE -ne 0) { throw 'Maven release build failed.' }
    } finally { Pop-Location }

    $jarPath = Join-Path $backendDir 'target\jena-ripper.jar'
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw "Production JAR was not created: $jarPath" }
    if ((Get-Item -LiteralPath $jarPath).Length -le 0) { throw "Production JAR is empty: $jarPath" }
    Write-Host '[OK] Backend release JAR'
    Write-Host

    foreach ($platform in $platforms) {
        $stage = "$($platform.Label) package"
        $platformWork = Join-Path $workDir $platform.Id
        $packageRoot = Join-Path $platformWork 'Jena-Ripper'
        $packageRuntime = Join-Path $packageRoot 'runtime'
        New-Item -ItemType Directory -Force -Path $packageRoot | Out-Null
        Copy-Item -LiteralPath $jarPath -Destination (Join-Path $packageRoot 'jena-ripper.jar') -Force
        Copy-Item -LiteralPath (Join-Path $runtimeCache $platform.Id) -Destination $packageRuntime -Recurse -Force
        if ($platform.Template -eq 'windows') {
            Copy-Item -LiteralPath (Join-Path $portableTemplates 'windows\start.bat') -Destination (Join-Path $packageRoot 'start.bat')
            Copy-Item -LiteralPath (Join-Path $portableTemplates 'windows\README.txt') -Destination (Join-Path $packageRoot 'README.txt')
        } else {
            Copy-Item -LiteralPath (Join-Path $portableTemplates 'macos\Jena Ripper.command') -Destination (Join-Path $packageRoot 'Jena Ripper.command')
            Copy-Item -LiteralPath (Join-Path $portableTemplates 'macos\README.txt') -Destination (Join-Path $packageRoot 'README.txt')
        }
        $archivePath = Join-Path $releaseDir $platform.Archive
        if ($platform.Template -eq 'windows') {
            Compress-Archive -LiteralPath $packageRoot -DestinationPath $archivePath -CompressionLevel Optimal
        } else {
            New-UnixZip $packageRoot $archivePath
            Assert-MacLauncherExecutable $archivePath
        }
        if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf) -or (Get-Item -LiteralPath $archivePath).Length -le 0) {
            throw "Portable archive was not created: $archivePath"
        }
        $platform | Add-Member -NotePropertyName Result -NotePropertyValue (Get-Item -LiteralPath $archivePath) -Force
    }

    $checksumPath = Join-Path $releaseDir 'SHA256SUMS.txt'
    $checksumLines = foreach ($platform in $platforms) {
        $hash = (Get-FileHash -LiteralPath $platform.Result.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($platform.Archive)"
    }
    [IO.File]::WriteAllLines($checksumPath, $checksumLines, [Text.UTF8Encoding]::new($false))

    $expected = @($platforms.Archive) + 'SHA256SUMS.txt' | Sort-Object
    $actual = Get-ChildItem -LiteralPath $releaseDir -File | Select-Object -ExpandProperty Name | Sort-Object
    if (($actual -join '|') -ne ($expected -join '|')) {
        throw "Unexpected release output. Expected: $($expected -join ', '). Actual: $($actual -join ', ')"
    }

    Assert-PathInside $workDir $releaseDir
    Remove-Item -LiteralPath $workDir -Recurse -Force
    foreach ($platform in $platforms) {
        Write-Host "[OK] $($platform.Label)"
        Write-Host "     release/$($platform.Archive)"
        Write-Host "     $([Math]::Round($platform.Result.Length / 1MB, 2)) MB"
        Write-Host
    }
    Write-Host 'Portable release completed successfully.'
    exit 0
} catch {
    Write-Host
    Write-Host "[FAILED] $stage" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
