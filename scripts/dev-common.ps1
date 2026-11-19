<#
  dev-server.ps1 与 build-backend.ps1 的共用函数。

  把 Java / Maven / .env / jar 的查找口径收敛到一处：
  - 启动脚本和编译脚本不会再出现「一个能找到 Maven、另一个找不到」的差异
  - 判断 jar 是否落后于源码的逻辑只维护一份
#>

$ErrorActionPreference = 'Stop'

function Get-ProjectRoot {
    # 本文件位于 <root>\scripts，上一级就是项目根目录
    return (Split-Path -Parent $PSScriptRoot)
}

function Import-DotEnv {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Warning "未找到 .env（$Path），将使用 application.yml 的默认配置"
        return
    }
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { continue }
        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        # 兼容 KEY="value" / KEY='value' 写法
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Find-Java {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe'))
    }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates.Add($onPath.Source) }
    # IDE 下载的 JDK 通常放在用户目录，双击 cmd 启动时 PATH 里可能没有
    $jdkRoot = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $jdkRoot) {
        Get-ChildItem -Path $jdkRoot -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin\java.exe')) }
    }
    foreach ($path in $candidates) {
        if (Test-Path -LiteralPath $path) { return $path }
    }
    throw '未找到 java.exe：请安装 JDK 21，或设置 JAVA_HOME。'
}

function Resolve-JavaHome {
    param([string]$Java)
    if ($env:JAVA_HOME) { return }
    # Maven 依赖 JAVA_HOME；从已找到的 java.exe 反推，避免用户再配一遍
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $Java)
}

function Find-Maven {
    $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $traeMaven = Join-Path $env:USERPROFILE '.trae-cn\tools\maven\latest\bin\mvn.cmd'
    if (Test-Path -LiteralPath $traeMaven) { return $traeMaven }
    $wrapperRoot = Join-Path $env:USERPROFILE '.m2\wrapper\dists'
    if (Test-Path -LiteralPath $wrapperRoot) {
        $found = Get-ChildItem -Path $wrapperRoot -Recurse -Filter 'mvn.cmd' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

function Find-BootstrapJar {
    param([string]$BootstrapDir)
    $targetDir = Join-Path $BootstrapDir 'target'
    if (-not (Test-Path -LiteralPath $targetDir)) { return $null }
    return Get-ChildItem -Path $targetDir -Filter 'baseplate-bootstrap-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Get-LatestBackendSourceTime {
    param([string]$BaseplateDir)
    $files = New-Object System.Collections.Generic.List[System.IO.FileInfo]

    $rootPom = Join-Path $BaseplateDir 'pom.xml'
    if (Test-Path -LiteralPath $rootPom) { $files.Add((Get-Item -LiteralPath $rootPom)) }

    # 只看各模块的 pom 与 src：跳过 target 和 .mvn-repo，避免扫描大量依赖文件
    foreach ($module in Get-ChildItem -LiteralPath $BaseplateDir -Directory -Filter 'baseplate-*') {
        $modulePom = Join-Path $module.FullName 'pom.xml'
        if (Test-Path -LiteralPath $modulePom) { $files.Add((Get-Item -LiteralPath $modulePom)) }
        $srcDir = Join-Path $module.FullName 'src'
        if (Test-Path -LiteralPath $srcDir) {
            Get-ChildItem -LiteralPath $srcDir -Recurse -File | ForEach-Object { $files.Add($_) }
        }
    }

    return ($files | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1)
}

function Test-BackendJarStale {
    param(
        [Parameter(Mandatory = $true)][string]$BaseplateDir,
        [string]$JarPath
    )
    if (-not $JarPath -or -not (Test-Path -LiteralPath $JarPath)) { return $true }
    $latestSource = Get-LatestBackendSourceTime -BaseplateDir $BaseplateDir
    if (-not $latestSource) { return $false }
    return $latestSource.LastWriteTimeUtc -gt (Get-Item -LiteralPath $JarPath).LastWriteTimeUtc
}

function Invoke-BackendBuild {
    param(
        [Parameter(Mandatory = $true)][string]$BaseplateDir,
        [Parameter(Mandatory = $true)][string]$Maven,
        [switch]$Quiet
    )
    $mavenArgs = @('-pl', 'baseplate-bootstrap', '-am', '-DskipTests')
    if ($Quiet) { $mavenArgs = @('-q') + $mavenArgs }
    # clean 不能省：移动/删除源码后 target 里会残留旧 class，
    # 打包时旧 class 和新 class 一起进 jar，启动会报同名 Bean 冲突。
    $mavenArgs += @('clean', 'package')

    Push-Location $BaseplateDir
    try {
        & $Maven @mavenArgs
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($code -ne 0) {
        throw "后端编译失败（退出码 $code）"
    }
}
