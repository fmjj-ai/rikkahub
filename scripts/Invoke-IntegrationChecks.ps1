param(
    [string[]]$Tasks = @(':ai:testDebugUnitTest', ':app:testDebugUnitTest', ':mediagen:testDebugUnitTest'),
    [ValidateRange(1, 2)]
    [int]$MaxWorkers = 2
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$java = 'C:\Program Files\Java\jdk-21\bin\java.exe'
$wrapperJar = Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.jar'
if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $wrapperJar)) {
    throw '缺少项目既有 JDK 或 Gradle wrapper，未尝试安装或修改环境。'
}
if ($Tasks.Count -eq 0 -or @($Tasks | Where-Object { $_ -notmatch '^:[A-Za-z0-9:_-]+$' }).Count -gt 0) {
    throw '请传入明确的 Gradle 任务路径，例如 :app:testDebugUnitTest。'
}

$wrapper = @('-Dfile.encoding=UTF-8', '-classpath', $wrapperJar, 'org.gradle.wrapper.GradleWrapperMain')
$options = @(
    '--offline', '--no-daemon', "--max-workers=$MaxWorkers",
    '-Dorg.gradle.parallel=false', '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8',
    '-Pkotlin.compiler.execution.strategy=in-process', '--console=plain', '--quiet'
)
$buildExit = 1
Push-Location -LiteralPath $projectRoot
try {
    # Kotlin 使用构建进程，不再启动长期占内存的独立编译 daemon。
    & $java @wrapper @options @Tasks
    $buildExit = $LASTEXITCODE
} finally {
    # 成功、失败均关闭 Gradle；不按名称批量终止 Java 或其他项目进程。
    & $java @wrapper --stop --console=plain
    Pop-Location
}
if ($buildExit -ne 0) {
    throw "集成校验失败，Gradle 退出码：$buildExit"
}
Write-Output '集成校验通过；已执行 Gradle daemon 清理。'
