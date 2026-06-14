param(
    [string]$Python = "D:\Anaconda3\python.exe"
)

$ErrorActionPreference = "Stop"

$Repo = (Resolve-Path ".").Path
$EvidenceDir = $PSScriptRoot
$FinalDir = Split-Path -Parent $EvidenceDir
$PackageDir = (Get-ChildItem -LiteralPath $FinalDir -Directory | Where-Object { $_.Name -like "05_*" } | Select-Object -First 1).FullName
if (-not $PackageDir) {
    throw "Could not locate package directory under $FinalDir"
}
$LogDir = Join-Path $EvidenceDir "verification_logs"
$SummaryPath = Join-Path $LogDir "command_summary.json"
$Utf8NoBom = New-Object System.Text.UTF8Encoding $false
$script:Summary = @()

function Write-Utf8NoBom {
    param(
        [string]$Path,
        [string]$Text
    )
    [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Save-Summary {
    $json = $script:Summary | ConvertTo-Json -Depth 5
    Write-Utf8NoBom -Path $SummaryPath -Text ($json + [Environment]::NewLine)
}

function Assert-Inside {
    param(
        [string]$Path,
        [string]$Parent
    )
    $full = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    if (-not $full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify path outside expected parent: $full"
    }
}

function Remove-DirectoryInside {
    param(
        [string]$Path,
        [string]$Parent
    )
    if (Test-Path -LiteralPath $Path) {
        $resolved = (Resolve-Path -LiteralPath $Path).Path
        Assert-Inside -Path $resolved -Parent $Parent
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

function ConvertFrom-CodePoints {
    param([int[]]$CodePoints)
    return -join ($CodePoints | ForEach-Object { [char]$_ })
}

function Remove-RawBackstageExtracts {
    $extractDir = Join-Path $EvidenceDir "extracted_text"
    if (-not (Test-Path -LiteralPath $extractDir)) {
        return
    }

    $chatWord = ConvertFrom-CodePoints @(0x7FA4, 0x804A)
    $chatRecordWord = ConvertFrom-CodePoints @(0x804A, 0x5929, 0x8BB0, 0x5F55)
    Get-ChildItem -LiteralPath $extractDir -File | Where-Object {
        $_.Name.Contains($chatWord) -or $_.Name.Contains($chatRecordWord)
    } | ForEach-Object {
        Remove-Item -LiteralPath $_.FullName -Force
    }

    $manifest = Join-Path $extractDir "manifest.json"
    if (Test-Path -LiteralPath $manifest) {
        $items = Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
        $filtered = @($items | Where-Object {
            $text = "$($_.path) $($_.out)"
            -not $text.Contains($chatWord) -and -not $text.Contains($chatRecordWord)
        })
        $json = $filtered | ConvertTo-Json -Depth 5
        Write-Utf8NoBom -Path $manifest -Text ($json + [Environment]::NewLine)
    }
}

function Invoke-Logged {
    param(
        [string]$Display,
        [string]$Command,
        [string[]]$Arguments
    )

    $slug = ($Display -replace '[^\w.-]+', '_').Trim('_')
    $logPath = Join-Path $LogDir "$slug.log"
    Write-Utf8NoBom -Path $logPath -Text ("# $Display`r`n# Started: $(Get-Date -Format s)`r`n`r`n")

    $start = Get-Date
    & $Command @Arguments 2>&1 | Tee-Object -FilePath $logPath -Append
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } else { 0 }
    $end = Get-Date

    Add-Content -LiteralPath $logPath -Encoding UTF8 -Value "`r`n# Finished: $($end.ToString('s'))"
    Add-Content -LiteralPath $logPath -Encoding UTF8 -Value "# ExitCode: $exitCode"
    $script:Summary += [pscustomobject]@{
        command = $Display
        exit_code = $exitCode
        started_at = $start.ToString("s")
        finished_at = $end.ToString("s")
        log = $logPath
    }
    Save-Summary

    if ($exitCode -ne 0) {
        throw "$Display failed with exit code $exitCode"
    }
}

New-Item -ItemType Directory -Force -Path $LogDir, $PackageDir | Out-Null
Set-Location $Repo

$strayDocs = Join-Path $Repo "docs\docs"
Remove-DirectoryInside -Path $strayDocs -Parent (Join-Path $Repo "docs")

if (-not (Test-Path -LiteralPath $Python)) {
    $Python = (Get-Command python).Source
}

Invoke-Logged -Display "generate_final_deliverables.py" -Command $Python -Arguments @(
    (Join-Path $EvidenceDir "generate_final_deliverables.py")
)

Remove-RawBackstageExtracts

Invoke-Logged -Display "git diff --check" -Command "git" -Arguments @("diff", "--check")
Invoke-Logged -Display "mvn -q -DskipTests compile" -Command "mvn" -Arguments @("-q", "-DskipTests", "compile")
Invoke-Logged -Display "mvn -q test" -Command "mvn" -Arguments @("-q", "test")
Invoke-Logged -Display "mvn -q -DskipTests package" -Command "mvn" -Arguments @("-q", "-DskipTests", "package")
Invoke-Logged -Display "package-stable.ps1" -Command "powershell.exe" -Arguments @(
    "-NoProfile",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    ".\scripts\package-stable.ps1"
)

$jar = Join-Path $Repo "target\image-manager-1.0.0.jar"
$portableZip = Join-Path $Repo "target\DigitalImageManager-windows-portable.zip"
$releaseDir = Join-Path $Repo "target\DigitalImageManager-release"

if (-not (Test-Path -LiteralPath $jar)) {
    throw "Missing target JAR: $jar"
}
Copy-Item -LiteralPath $jar -Destination (Join-Path $PackageDir "image-manager-1.0.0.jar") -Force

if (Test-Path -LiteralPath $portableZip) {
    Copy-Item -LiteralPath $portableZip -Destination (Join-Path $PackageDir "DigitalImageManager-windows-portable.zip") -Force
}

if (Test-Path -LiteralPath $releaseDir) {
    $releaseDest = Join-Path $PackageDir "DigitalImageManager-release"
    Remove-DirectoryInside -Path $releaseDest -Parent $PackageDir
    Copy-Item -LiteralPath $releaseDir -Destination $releaseDest -Recurse -Force
}

Invoke-Logged -Display "validate_deliverables.py" -Command $Python -Arguments @(
    (Join-Path $EvidenceDir "validate_deliverables.py")
)

Write-Host "Final verification completed."
