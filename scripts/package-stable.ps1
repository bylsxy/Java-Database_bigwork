param(
    [switch]$SkipZip
)

$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$TargetDir = Join-Path $ProjectRoot "target"
$BuildDir = Join-Path $TargetDir "stable-build"
$ClassesDir = Join-Path $TargetDir "classes"
$DepsDir = Join-Path $TargetDir "deps"
$PackageDir = Join-Path $TargetDir "stable-package"
$InputDir = Join-Path $PackageDir "input"
$ReleaseRoot = Join-Path $TargetDir "DigitalImageManager-stable"
$AppName = "DigitalImageManager"
$AppJarName = "image-manager-1.0.0.jar"
$PlainJar = Join-Path $InputDir $AppJarName
$FatJar = Join-Path $PackageDir "image-manager-1.0.0-stable-fat.jar"

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
    param([string]$RelativePath)

    $path = Join-Path $ProjectRoot $RelativePath
    if (Test-Path $path) {
        $resolved = (Resolve-Path $path).Path
        Assert-InProject $resolved
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

function Copy-Dependency {
    param(
        [string]$MavenRepository,
        [string]$RelativeJarPath
    )

    $relativePath = $RelativeJarPath -replace '/', [System.IO.Path]::DirectorySeparatorChar
    $source = Join-Path $MavenRepository $relativePath
    if (-not (Test-Path $source)) {
        $fileName = Split-Path $RelativeJarPath -Leaf
        $source = Get-ChildItem -Path $MavenRepository -Recurse -File -Filter $fileName |
                Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $source -or -not (Test-Path $source)) {
        throw "Missing dependency: $RelativeJarPath. Please run Maven once or keep the local .m2 repository available on the build machine."
    }
    Copy-Item -LiteralPath $source -Destination $DepsDir -Force
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

Set-Location $ProjectRoot
Assert-InProject $TargetDir

Write-Host "Cleaning old package output..."
Remove-ProjectPath "target"
New-Item -ItemType Directory -Force -Path $BuildDir, $ClassesDir, $DepsDir, $InputDir, $ReleaseRoot | Out-Null

$m2 = Join-Path $env:USERPROFILE ".m2\repository"
if (-not (Test-Path $m2)) {
    throw "Local Maven repository not found: $m2"
}

$dependencyJars = @(
    "org/openjfx/javafx-base/21.0.6/javafx-base-21.0.6-win.jar",
    "org/openjfx/javafx-controls/21.0.6/javafx-controls-21.0.6-win.jar",
    "org/openjfx/javafx-fxml/21.0.6/javafx-fxml-21.0.6-win.jar",
    "org/openjfx/javafx-graphics/21.0.6/javafx-graphics-21.0.6-win.jar",
    "org/openjfx/javafx-media/21.0.6/javafx-media-21.0.6-win.jar",
    "org/openjfx/javafx-swing/21.0.6/javafx-swing-21.0.6-win.jar",
    "org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar",
    "com/zaxxer/HikariCP/6.2.1/HikariCP-6.2.1.jar",
    "org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar",
    "ch/qos/logback/logback-classic/1.5.15/logback-classic-1.5.15.jar",
    "ch/qos/logback/logback-core/1.5.15/logback-core-1.5.15.jar",
    "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar",
    "com/squareup/okio/okio/3.6.0/okio-3.6.0.jar",
    "com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar",
    "org/jetbrains/kotlin/kotlin-stdlib/1.8.21/kotlin-stdlib-1.8.21.jar",
    "org/jetbrains/kotlin/kotlin-stdlib-common/1.8.21/kotlin-stdlib-common-1.8.21.jar",
    "org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.8.21/kotlin-stdlib-jdk7-1.8.21.jar",
    "org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.8.21/kotlin-stdlib-jdk8-1.8.21.jar",
    "org/jetbrains/annotations/13.0/annotations-13.0.jar",
    "com/fasterxml/jackson/core/jackson-annotations/2.18.2/jackson-annotations-2.18.2.jar",
    "com/fasterxml/jackson/core/jackson-core/2.18.2/jackson-core-2.18.2.jar",
    "com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2.jar",
    "com/github/lookfirst/sardine/5.12/sardine-5.12.jar",
    "org/apache/httpcomponents/httpclient/4.5.14/httpclient-4.5.14.jar",
    "org/apache/httpcomponents/httpcore/4.4.16/httpcore-4.4.16.jar",
    "commons-codec/commons-codec/1.18.0/commons-codec-1.18.0.jar",
    "commons-logging/commons-logging/1.2/commons-logging-1.2.jar",
    "org/apache/commons/commons-lang3/3.17.0/commons-lang3-3.17.0.jar",
    "org/apache/commons/commons-collections4/4.5.0/commons-collections4-4.5.0.jar",
    "org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar",
    "jakarta/xml/bind/jakarta.xml.bind-api/4.0.0/jakarta.xml.bind-api-4.0.0.jar",
    "jakarta/activation/jakarta.activation-api/2.1.4/jakarta.activation-api-2.1.4.jar",
    "org/glassfish/jaxb/jaxb-runtime/4.0.2/jaxb-runtime-4.0.2.jar",
    "org/glassfish/jaxb/jaxb-core/4.0.2/jaxb-core-4.0.2.jar",
    "org/glassfish/jaxb/txw2/4.0.2/txw2-4.0.2.jar",
    "com/sun/istack/istack-commons-runtime/4.1.2/istack-commons-runtime-4.1.2.jar",
    "org/checkerframework/checker-qual/3.48.3/checker-qual-3.48.3.jar",
    "org/checkerframework/checker-compat-qual/2.0.0/checker-compat-qual-2.0.0.jar"
)

Write-Host "Copying runtime dependencies..."
foreach ($dependencyJar in $dependencyJars) {
    Copy-Dependency -MavenRepository $m2 -RelativeJarPath $dependencyJar
}

$sourcesFile = Join-Path $BuildDir "sources.txt"
Get-ChildItem -Path (Join-Path $ProjectRoot "src\main\java") -Recurse -Filter "*.java" -File |
        Sort-Object FullName |
        ForEach-Object { $_.FullName } |
        Set-Content -Encoding ASCII -Path $sourcesFile

$compileClasspath = (Get-ChildItem -Path $DepsDir -Filter "*.jar" -File |
        Sort-Object Name |
        ForEach-Object { $_.FullName }) -join [System.IO.Path]::PathSeparator

Write-Host "Compiling Java sources..."
$javacLog = Join-Path $BuildDir "javac.log"
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javacOutput = & javac -encoding UTF-8 -cp $compileClasspath -d $ClassesDir "@$sourcesFile" 2>&1
$javacExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
$javacOutput | Set-Content -Encoding UTF8 -Path $javacLog
if ($javacExitCode -ne 0) {
    $requiredClasses = @(
        "com\imagemanager\Launcher.class",
        "com\imagemanager\App.class",
        "com\imagemanager\controller\MainController.class",
        "com\imagemanager\controller\DatabaseSetupDialog.class",
        "com\imagemanager\service\DatabaseBootstrapService.class"
    )
    $classesPresent = $true
    foreach ($requiredClass in $requiredClasses) {
        if (-not (Test-Path (Join-Path $ClassesDir $requiredClass))) {
            $classesPresent = $false
        }
    }
    if (-not $classesPresent) {
        Get-Content -Path $javacLog
        throw "javac failed. See $javacLog"
    }
    Write-Warning "javac returned $javacExitCode after writing classes. Continuing because required class files were produced; see $javacLog."
}

Write-Host "Copying resources..."
Copy-Item -Path (Join-Path $ProjectRoot "src\main\resources\*") -Destination $ClassesDir -Recurse -Force
$sqlTarget = Join-Path $ClassesDir "sql"
New-Item -ItemType Directory -Force -Path $sqlTarget | Out-Null
Copy-Item -Path (Join-Path $ProjectRoot "sql\*.sql") -Destination $sqlTarget -Force

$manifestFile = Join-Path $BuildDir "MANIFEST.MF"
@"
Manifest-Version: 1.0
Main-Class: com.imagemanager.Launcher
Enable-Native-Access: ALL-UNNAMED

"@ | Set-Content -Encoding ASCII -Path $manifestFile

Write-Host "Creating plain application jar..."
Invoke-RequiredTool "jar" @("cfm", $PlainJar, $manifestFile, "-C", $ClassesDir, ".")
Copy-Item -Path (Join-Path $DepsDir "*.jar") -Destination $InputDir -Force

Write-Host "Creating stable fat jar..."
$fatStaging = Join-Path $PackageDir "fat-staging"
New-Item -ItemType Directory -Force -Path $fatStaging | Out-Null
Push-Location $fatStaging
try {
    foreach ($jarFile in Get-ChildItem -Path $InputDir -Filter "*.jar" -File | Sort-Object Name) {
        Invoke-RequiredTool "jar" @("xf", $jarFile.FullName)
    }
}
finally {
    Pop-Location
}
$metaInf = Join-Path $fatStaging "META-INF"
if (Test-Path $metaInf) {
    Get-ChildItem -Path $metaInf -Recurse -Include "*.SF", "*.DSA", "*.RSA" -File |
            Remove-Item -Force
}
Invoke-RequiredTool "jar" @("cfe", $FatJar, "com.imagemanager.Launcher", "-C", $fatStaging, ".")
Copy-Item -LiteralPath $FatJar -Destination (Join-Path $TargetDir $AppJarName) -Force

Write-Host "Creating app-image exe with bundled runtime..."
Invoke-RequiredTool "jpackage" @(
    "--type", "app-image",
    "--dest", $ReleaseRoot,
    "--input", $InputDir,
    "--name", $AppName,
    "--main-jar", $AppJarName,
    "--main-class", "com.imagemanager.Launcher",
    "--java-options", "--enable-native-access=ALL-UNNAMED"
)

Copy-Item -LiteralPath $FatJar -Destination (Join-Path $ReleaseRoot "image-manager-1.0.0-stable-fat.jar") -Force

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$asciiEncoding = [System.Text.Encoding]::ASCII

$readmeText = @"
Digital Image Manager - Stable Portable Package

Recommended start:
1. Double-click START.cmd, or open the DigitalImageManager folder and double-click DigitalImageManager.exe.
2. This portable exe bundles Java runtime, JavaFX, and all application jar dependencies. A new PC does not need a separate JDK, Maven, or JavaFX install to run the exe.
3. If PostgreSQL is not installed on the new PC, the app opens the main window and shows the database setup wizard. Click the PostgreSQL install button in the wizard and install PostgreSQL 16 or later.
4. Remember the postgres user password created during PostgreSQL installation. If the password is unknown or rejected, type the password again in the wizard, click Save Config, then click Test Connection.
5. After the connection test passes, click Create Database And Initialize. The app creates the image_manager database and runs the bundled schema.sql.
6. Local database config is saved to %LOCALAPPDATA%\DigitalImageManager\database.properties. When copied to another PC, fill it again through the wizard.

Fallback start:
- image-manager-1.0.0-stable-fat.jar is a backup fat jar. It requires Java 21 or later on the target PC.
"@
[System.IO.File]::WriteAllText((Join-Path $ReleaseRoot "START_HERE.txt"), $readmeText, $utf8NoBom)

$launcherText = @"
@echo off
cd /d "%~dp0DigitalImageManager"
start "" "DigitalImageManager.exe"
"@
[System.IO.File]::WriteAllText((Join-Path $ReleaseRoot "START.cmd"), $launcherText, $asciiEncoding)

if (-not $SkipZip) {
    $zipPath = Join-Path $TargetDir "DigitalImageManager-stable-portable.zip"
    Write-Host "Creating portable zip..."
    Compress-Archive -Path (Join-Path $ReleaseRoot "*") -DestinationPath $zipPath -Force
}

$hashFile = Join-Path $ReleaseRoot "SHA256SUMS.txt"
$hashTargets = @(
    (Join-Path $ReleaseRoot "DigitalImageManager\DigitalImageManager.exe"),
    (Join-Path $ReleaseRoot "image-manager-1.0.0-stable-fat.jar"),
    (Join-Path $TargetDir $AppJarName),
    (Join-Path $TargetDir "DigitalImageManager-stable-portable.zip")
) | Where-Object { Test-Path $_ }

$hashLines = foreach ($hashTarget in $hashTargets) {
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $hashTarget
    "$($hash.Hash)  $($hash.Path)"
}
[System.IO.File]::WriteAllLines($hashFile, [string[]]$hashLines, $asciiEncoding)

Write-Host ""
Write-Host "Stable package completed:"
Write-Host "  Release folder: $ReleaseRoot"
Write-Host "  EXE: $(Join-Path $ReleaseRoot 'DigitalImageManager\DigitalImageManager.exe')"
Write-Host "  Fat jar: $(Join-Path $ReleaseRoot 'image-manager-1.0.0-stable-fat.jar')"
if (-not $SkipZip) {
    Write-Host "  Zip: $(Join-Path $TargetDir 'DigitalImageManager-stable-portable.zip')"
}
Write-Host "  Hashes: $hashFile"
