<#
  前后端本地启动脚本（始终前台运行）。

  用法：
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-server.ps1 -Target backend
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-server.ps1 -Target frontend
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-server.ps1 -Target backend -DryRun

  设计要点：
    - Java / Node 都是当前控制台的子进程，Ctrl+C 或关闭窗口即退出，不会留后台服务。
    - .env 只注入当前进程环境，不修改系统环境变量。
    - Spring Boot 的 Flyway 迁移只在启动命令里显式开启，避免影响其它启动方式。
    - 启动前比较 jar 与源码时间：源码更新时自动 clean package，避免继续使用旧代码。
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('backend', 'frontend')]
    [string]$Target,

    # 只检查环境与路径，不真正启动服务，便于排查配置问题
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-common.ps1')

function Find-Node {
    $onPath = Get-Command node.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $default = Join-Path $env:ProgramFiles 'nodejs\node.exe'
    if (Test-Path -LiteralPath $default) { return $default }
    throw '未找到 node.exe：请安装 Node.js 18+。'
}

function Exit-WithChildCode {
    param([int]$Code)
    # 130 / 0xC000013A 都是用户 Ctrl+C 或关闭窗口导致的，按正常停止处理
    if ($Code -eq 0 -or $Code -eq 130 -or $Code -eq -1073741510 -or $Code -eq 3221225786) {
        exit 0
    }
    exit $Code
}

function Start-Backend {
    $root = Get-ProjectRoot
    $baseplate = Join-Path $root 'baseplate'
    $bootstrap = Join-Path $baseplate 'baseplate-bootstrap'
    if (-not (Test-Path -LiteralPath $bootstrap)) {
        throw "找不到后端目录：$bootstrap"
    }

    Import-DotEnv (Join-Path $root '.env')

    $java = Find-Java
    Resolve-JavaHome $java

    $maven = Find-Maven
    $jar = Find-BootstrapJar -BootstrapDir $bootstrap
    $jarPath = $null
    if ($jar) { $jarPath = $jar.FullName }
    $jarStale = Test-BackendJarStale -BaseplateDir $baseplate -JarPath $jarPath

    if ($DryRun) {
        Write-Host "Java    : $java" -ForegroundColor DarkGray
        if ($jar) { Write-Host "Jar     : $($jar.FullName)" -ForegroundColor DarkGray }
        if ($jarStale) {
            Write-Host 'DryRun  ：jar 缺失或落后于源码，请先运行 build-backend.cmd 编译。' -ForegroundColor Yellow
        } else {
            Write-Host "DryRun  ：检查通过，未启动后端。数据库：$($env:BASEPLATE_DB_URL)" -ForegroundColor Green
        }
        return
    }

    if ($jarStale) {
        if (-not $maven) {
            throw '没有最新的后端 jar，也没找到 Maven。请先执行：build-backend.cmd 或 mvn -pl baseplate-bootstrap -am clean package -DskipTests'
        }
        if ($jar) {
            Write-Host '检测到源码比 jar 新，先重新编译后端...' -ForegroundColor Yellow
        } else {
            Write-Host '首次启动：正在编译后端（首次下载依赖会比较慢）...' -ForegroundColor Yellow
        }
        Invoke-BackendBuild -BaseplateDir $baseplate -Maven $maven -Quiet
        $jar = Find-BootstrapJar -BootstrapDir $bootstrap
        if (-not $jar) { throw '编译完成但没有找到 baseplate-bootstrap jar' }
    }

    # 与 run-server.sh 保持一致：迁移脚本按模块目录显式列出
    $flywayLocations = @(
        'classpath:db/migration/baseplate',
        'classpath:db/migration/identity',
        'classpath:db/migration/audit',
        'classpath:db/migration/platform',
        'classpath:db/migration/metadata',
        'classpath:db/migration/rule',
        'classpath:db/migration/workflow',
        'classpath:db/migration/agent',
        'classpath:db/migration/cend'
    ) -join ','

    Write-Host "Java    : $java" -ForegroundColor DarkGray
    Write-Host "Jar     : $($jar.FullName)" -ForegroundColor DarkGray

    Write-Host '启动后端：http://127.0.0.1:8080（Ctrl+C 或关闭窗口即停止）' -ForegroundColor Green
    Push-Location $baseplate
    try {
        & $java -jar $jar.FullName '--spring.flyway.enabled=true' "--spring.flyway.locations=$flywayLocations"
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    Exit-WithChildCode $exitCode
}

function Start-Frontend {
    $root = Get-ProjectRoot
    $console = Join-Path $root 'baseplate-console'
    if (-not (Test-Path -LiteralPath $console)) {
        throw "找不到前端目录：$console"
    }

    Push-Location $console
    try {
        $node = Find-Node
        $viteEntry = Join-Path $console 'node_modules\vite\bin\vite.js'
        if (-not (Test-Path -LiteralPath $viteEntry)) {
            $npm = Join-Path (Split-Path -Parent $node) 'npm.cmd'
            if (-not (Test-Path -LiteralPath $npm)) {
                throw '未找到 npm.cmd：请确认 Node.js 安装完整。'
            }
            Write-Host '首次启动：正在安装前端依赖...' -ForegroundColor Yellow
            & $npm install
            $installCode = $LASTEXITCODE
            if ($installCode -ne 0) {
                throw "npm install 失败（退出码 $installCode）"
            }
        }

        Write-Host "Node    : $node" -ForegroundColor DarkGray
        Write-Host "Vite    : $viteEntry" -ForegroundColor DarkGray
        if ($DryRun) {
            Write-Host 'DryRun  ：检查通过，未启动前端。' -ForegroundColor Green
            return
        }

        Write-Host '启动前端：http://127.0.0.1:5173（Ctrl+C 或关闭窗口即停止）' -ForegroundColor Green
        # 直接调用 vite.js，避免 npm.cmd 在 Ctrl+C 时弹「Terminate batch job (Y/N)?」
        & $node $viteEntry
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    Exit-WithChildCode $exitCode
}

try {
    switch ($Target) {
        'backend' { Start-Backend }
        'frontend' { Start-Frontend }
    }
} catch {
    Write-Host ''
    Write-Host "启动失败：$($_.Exception.Message)" -ForegroundColor Red
    exit 2
}
