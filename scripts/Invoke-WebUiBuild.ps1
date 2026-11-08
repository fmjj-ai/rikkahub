[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
# Gradle 重定向子进程输出时仍保持 UTF-8；只影响本次 PowerShell 进程。
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$webUiDir = [IO.Path]::GetFullPath((Join-Path $projectRoot 'web-ui'))
$sourceDir = [IO.Path]::GetFullPath((Join-Path $webUiDir 'build\client'))
$targetDir = [IO.Path]::GetFullPath((Join-Path $webUiDir '..\web\src\main\resources\static'))
$expectedTarget = [IO.Path]::GetFullPath((Join-Path $projectRoot 'web\src\main\resources\static'))

# copy.ts 会递归清理目标，执行前固定工作目录并核对其完整路径。
$workspacePrefix = $projectRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $sourceDir.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $targetDir.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $targetDir.Equals($expectedTarget, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Web 静态资源路径越出预期目录，未执行构建或清理。'
}

$node = (Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$routerCli = Join-Path $webUiDir 'node_modules\@react-router\dev\bin.js'
$tsxCli = Join-Path $webUiDir 'node_modules\tsx\dist\cli.mjs'
$copyScript = Join-Path $webUiDir 'copy.ts'
foreach ($path in @($routerCli, $tsxCli, $copyScript)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "缺少既有 Web 构建入口：$path；未尝试安装依赖。"
    }
}

Push-Location -LiteralPath $webUiDir
try {
    & $node $routerCli build
    if ($LASTEXITCODE -ne 0) {
        throw "Web 构建失败，退出码：$LASTEXITCODE；未清理现有静态资源。"
    }
    if (-not (Test-Path -LiteralPath $sourceDir -PathType Container)) {
        throw "缺少构建产物：$sourceDir；未清理现有静态资源。"
    }
    Write-Output "复制源：$sourceDir；静态资源目标：$targetDir"
    & $node $tsxCli $copyScript
    if ($LASTEXITCODE -ne 0) {
        throw "Web 静态资源复制失败，退出码：$LASTEXITCODE。"
    }
} finally {
    Pop-Location
}
