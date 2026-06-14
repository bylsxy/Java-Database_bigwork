param($Original,$Doubao,$OutDir)
$ErrorActionPreference='Stop'
function Export-Ppt($path,$dir){
  $abs=(Resolve-Path -LiteralPath $path).Path
  $target=(Resolve-Path -LiteralPath $dir).Path
  $ppt = New-Object -ComObject PowerPoint.Application
  $ppt.Visible = [Microsoft.Office.Core.MsoTriState]::msoTrue
  try {
    $pres = $ppt.Presentations.Open($abs, $true, $false, $false)
    try { $pres.Export($target, 'PNG', 1600, 900) } finally { $pres.Close() }
  } finally { $ppt.Quit() }
}
New-Item -ItemType Directory -Force -Path (Join-Path $OutDir 'original') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutDir 'doubao') | Out-Null
Export-Ppt $Original (Join-Path $OutDir 'original')
Export-Ppt $Doubao (Join-Path $OutDir 'doubao')
