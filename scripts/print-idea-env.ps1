<#
  把仓库根目录 .env 转成 IDEA 可粘贴的环境变量串。

  用法：
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\print-idea-env.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\print-idea-env.ps1 -IncludeSecrets

  输出：
    1) 一整行 IDEA Environment variables（K=V;K2=V2）
    2) 运行配置要点提示
#>
param(
  # 是否包含密码类密钥（默认包含，本机自用；贴到聊天/工单时务必用 -ExcludeSecrets）
  [switch]$ExcludeSecrets
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
  throw "找不到 .env：$envFile"
}

$pairs = @()
Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
  $line = $_.Trim()
  if ($line -eq '' -or $line.StartsWith('#')) { return }
  if ($line -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { return }
  $key = $matches[1]
  $value = $matches[2]
  if ($ExcludeSecrets -and $key -match '(PASSWORD|SECRET|KEY|TOKEN)') {
    $value = '***'
  }
  $pairs += "$key=$value"
}

Write-Host '=== IDEA Environment variables（整行复制）===' -ForegroundColor Green
Write-Host ($pairs -join ';')
Write-Host ''
Write-Host '=== Run/Debug Configuration 要点 ===' -ForegroundColor Green
Write-Host 'Main class        : com.example.baseplate.BootstrapApplication'
Write-Host 'Use classpath     : baseplate-bootstrap'
Write-Host "Working directory : $(Join-Path $root 'baseplate')   ← 必须是 baseplate 这一层"
Write-Host 'Program arguments : 留空（Flyway 已由 SPRING_FLYWAY_* 环境变量带入）'
Write-Host 'JRE               : Java 21'
Write-Host ''
Write-Host '启动成功的标志：' -ForegroundColor Yellow
Write-Host '  Tomcat started on port 8080'
Write-Host '  技能目录扫描完成: 17 个     ← 工作目录对不对的探针，为 0 就是目录错了'
