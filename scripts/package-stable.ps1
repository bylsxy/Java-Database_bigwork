param(
    [switch]$SkipZip,
    [switch]$SkipMaven
)

$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$TargetDir = Join-Path $ProjectRoot "target"
$InputDir = Join-Path $TargetDir "jpackage-input"
$ReleaseRoot = Join-Path $TargetDir "DigitalImageManager-release"
$AppImageDir = Join-Path $ReleaseRoot "DigitalImageManager"
$AppName = "DigitalImageManager"
$AppJarName = "image-manager-1.0.0.jar"
$AppJar = Join-Path $TargetDir $AppJarName
$ZipPath = Join-Path $TargetDir "DigitalImageManager-windows-portable.zip"
$IntermediateJars = @(
    (Join-Path $TargetDir "original-$AppJarName"),
    (Join-Path $TargetDir "image-manager-1.0.0-shaded.jar")
)

function Assert-InProject {
    param([string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $rootWithSeparator = $ProjectRoot.TrimEnd('\') + '\'
    if (-not $fullPath.Equals($ProjectRoot, [System.StringComparison]::OrdinalIgnoreCase) `
            -and -not $fullPath.StartsWith($rootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify path outside project: $fullPath"
    }
}

function Remove-ProjectPath {
    param([string]$Path)

    if (Test-Path $Path) {
        $resolved = (Resolve-Path $Path).Path
        Assert-InProject $resolved
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

function Invoke-RequiredTool {
    param(
        [string]$Tool,
        [string[]]$Arguments
    )

    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Tool failed with exit code $LASTEXITCODE"
    }
}

function ConvertFrom-CodePoints {
    param([int[]]$CodePoints)
    return -join ($CodePoints | ForEach-Object { [char]$_ })
}

Set-Location $ProjectRoot

if (-not $SkipMaven) {
    Write-Host "Building dependency-complete jar with Maven..."
    Invoke-RequiredTool "mvn" @("-DskipTests", "clean", "package")
} elseif (-not (Test-Path $AppJar)) {
    throw "Missing $AppJar. Run mvn -DskipTests clean package first, or omit -SkipMaven."
}

if (-not (Test-Path $AppJar)) {
    throw "Maven package did not produce $AppJar"
}
foreach ($intermediateJar in $IntermediateJars) {
    Remove-ProjectPath $intermediateJar
}

Write-Host "Preparing release folders..."
Remove-ProjectPath $InputDir
Remove-ProjectPath $ReleaseRoot
if (Test-Path $ZipPath) {
    Remove-ProjectPath $ZipPath
}
New-Item -ItemType Directory -Force -Path $InputDir, $ReleaseRoot | Out-Null
Copy-Item -LiteralPath $AppJar -Destination (Join-Path $InputDir $AppJarName) -Force
Copy-Item -LiteralPath $AppJar -Destination (Join-Path $ReleaseRoot $AppJarName) -Force

$ObjectCourseDir = ConvertFrom-CodePoints @(0x9762, 0x5411, 0x5BF9, 0x8C61, 0x7A0B, 0x5E8F, 0x4E0E, 0x8BBE, 0x8BA1)
$WritingDir = ConvertFrom-CodePoints @(0x6211, 0x4EEC, 0x7684, 0x5B9E, 0x9645, 0x5199, 0x4F5C)
$PracticeDir = ConvertFrom-CodePoints @(0x9762, 0x5411, 0x5BF9, 0x8C61, 0x7A0B, 0x5E8F, 0x8BBE, 0x8BA1, 0x5B9E, 0x8DF5)
$ClassDir = ConvertFrom-CodePoints @(0x32, 0x30, 0x32, 0x34, 0x7EA7, 0x8F6F, 0x4EF6, 0x5DE5, 0x7A0B, 0x52, 0x35, 0x73ED)
$GroupDir = ConvertFrom-CodePoints @(0x7B2C, 0x30, 0x37, 0x7EC4)
$CourseJarName = ConvertFrom-CodePoints @(0x9762, 0x5411, 0x5BF9, 0x8C61, 0x7A0B, 0x5E8F, 0x8BBE, 0x8BA1, 0x5B9E, 0x8DF5, 0x76EE, 0x6807, 0x4EE3, 0x7801, 0x2E, 0x4A, 0x41, 0x52)
$CourseJarTargets = @(
    (Join-Path $ProjectRoot (Join-Path "docs" (Join-Path $ObjectCourseDir (Join-Path $WritingDir $CourseJarName)))),
    (Join-Path $ProjectRoot (Join-Path "docs" (Join-Path $ObjectCourseDir (Join-Path $PracticeDir (Join-Path $ClassDir (Join-Path $GroupDir $CourseJarName))))))
) | Where-Object { Test-Path $_ }
foreach ($courseJarTarget in $CourseJarTargets) {
    Write-Host "Updating course target jar: $courseJarTarget"
    Copy-Item -LiteralPath $AppJar -Destination $courseJarTarget -Force
}

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$readmeText = @"
Digital Image Manager - Windows Portable Release

Recommended for a new computer:
1. Open the DigitalImageManager folder.
2. Double-click DigitalImageManager.exe.
3. No JDK, Maven, JavaFX, cmd, or PowerShell operation is required.
4. PostgreSQL is still required for database-backed features. If it is missing, the app opens the database setup wizard.

For a computer that already has Java 21 or later:
- Run image-manager-1.0.0.jar.
- This jar is dependency-complete and already includes application dependencies and sql/*.sql resources.

Local database config is saved under %LOCALAPPDATA%\DigitalImageManager\database.properties.
"@
[System.IO.File]::WriteAllText((Join-Path $ReleaseRoot "START_HERE.txt"), $readmeText, $utf8NoBom)

Write-Host "Creating portable app image with bundled runtime..."
Invoke-RequiredTool "jpackage" @(
    "--type", "app-image",
    "--dest", $ReleaseRoot,
    "--input", $InputDir,
    "--name", $AppName,
    "--main-jar", $AppJarName,
    "--main-class", "com.imagemanager.Launcher",
    "--java-options", "--enable-native-access=ALL-UNNAMED"
)

if (-not (Test-Path (Join-Path $AppImageDir "$AppName.exe"))) {
    throw "jpackage did not produce $AppImageDir\$AppName.exe"
}

if (-not $SkipZip) {
    Write-Host "Creating portable zip..."
    Compress-Archive -Path (Join-Path $ReleaseRoot "*") -DestinationPath $ZipPath -Force
}

$hashFile = Join-Path $ReleaseRoot "SHA256SUMS.txt"
$hashTargets = @(
    (Join-Path $AppImageDir "$AppName.exe"),
    (Join-Path $ReleaseRoot $AppJarName),
    $ZipPath
) | Where-Object { Test-Path $_ }

$hashLines = foreach ($hashTarget in $hashTargets) {
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $hashTarget
    "$($hash.Hash)  $($hash.Path)"
}
[System.IO.File]::WriteAllLines($hashFile, [string[]]$hashLines, $utf8NoBom)

Write-Host ""
Write-Host "Release package completed:"
Write-Host "  Jar: $(Join-Path $ReleaseRoot $AppJarName)"
Write-Host "  Exe: $(Join-Path $AppImageDir "$AppName.exe")"
if (-not $SkipZip) {
    Write-Host "  Zip: $ZipPath"
}
Write-Host "  Hashes: $hashFile"
