﻿<#
  后端编译脚本：clean package 生成可直接启动的 baseplate-bootstrap fat jar。

  用法：
    build-backend.cmd
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-backend.ps1

  为什么必须 clean：
    Maven 的增量编译不会删除「源码已不存在」的旧 .class。移动/重命名类之后，
    旧 class 会和新 class 一起进 jar，启动时出现同名 Bean 冲突
    （例如 ConflictingBeanDefinitionException）。
#>

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-common.ps1')

try {
    $root = Get-ProjectRoot
    $baseplate = Join-Path $root 'baseplate'
    if (-not (Test-Path -LiteralPath $baseplate)) {
        throw "找不到后端目录：$baseplate"
    }

    # Windows 会锁住正在运行的 jar；先提示停止后端，避免 Maven 在 clean 阶段报一堆删除失败
    $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        throw '检测到 8080 端口正在监听（后端可能仍在运行），请先停止后端再编译。'
    }

    Import-DotEnv (Join-Path $root '.env')

    $java = Find-Java
    Resolve-JavaHome $java
    $maven = Find-Maven
    if (-not $maven) {
        throw '未找到 Maven：请安装 mvn，或确认 %USERPROFILE%\.m2\wrapper\dists 存在。'
    }

    Write-Host "Java    : $java" -ForegroundColor DarkGray
    Write-Host "Maven   : $maven" -ForegroundColor DarkGray
    Write-Host '开始编译：clean package（跳过测试）...' -ForegroundColor Green

    $started = Get-Date
    Invoke-BackendBuild -BaseplateDir $baseplate -Maven $maven

    $jar = Find-BootstrapJar -BootstrapDir (Join-Path $baseplate 'baseplate-bootstrap')
    if (-not $jar) {
        throw '编译成功但没有找到 baseplate-bootstrap jar'
    }
    $seconds = [int]((Get-Date) - $started).TotalSeconds
    Write-Host ("编译完成（{0}s）：{1}" -f $seconds, $jar.FullName) -ForegroundColor Green
    Write-Host ("Jar 大小：{0:N1} MB" -f ($jar.Length / 1MB)) -ForegroundColor DarkGray
    Write-Host '现在运行 start-backend.cmd 会使用这个最新 jar。' -ForegroundColor Green
    exit 0
} catch {
    Write-Host ''
    Write-Host "编译失败：$($_.Exception.Message)" -ForegroundColor Red
    exit 2
}
