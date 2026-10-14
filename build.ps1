param([string[]]$Tasks = @(':core:engine:test', ':app:assembleDebug'))
$ErrorActionPreference = 'Stop'
$taskToolchain = Join-Path $PSScriptRoot '..\toolchain'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = (Get-ChildItem (Join-Path $taskToolchain 'java') -Directory | Select-Object -First 1).FullName }
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $taskToolchain 'gradle-cache' }
Push-Location $PSScriptRoot
try { & (Join-Path $taskToolchain 'gradle-8.13\bin\gradle.bat') @Tasks --console=plain; if ($LASTEXITCODE -ne 0) { throw "构建失败 $LASTEXITCODE" } } finally { Pop-Location }
