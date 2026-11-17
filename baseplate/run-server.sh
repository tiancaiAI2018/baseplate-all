#!/usr/bin/env bash
# 本地启动脚本：从村通后端的 .env 读取真实 PG/Redis 连接信息，不落配置文件。
# 用法：bash run-server.sh --server.port=18080 [--migrate]
# --migrate 会开启 Flyway 并带上全部模块的迁移目录（含 workflow），新表必须靠它建。
set -euo pipefail

MIGRATE_ARGS=()
APP_ARGS=()
for arg in "$@"; do
  if [ "$arg" = "--migrate" ]; then
    MIGRATE_ARGS=(
      "--spring.flyway.enabled=true"
      "--spring.flyway.locations=classpath:db/migration/baseplate,classpath:db/migration/identity,classpath:db/migration/audit,classpath:db/migration/platform,classpath:db/migration/metadata,classpath:db/migration/rule,classpath:db/migration/workflow"
    )
  else
    APP_ARGS+=("$arg")
  fi
done

JAVA_HOME="${JAVA_HOME:-C:/Users/wbh1/.jdks/graalvm-jdk-21.0.5}"
export JAVA_HOME

HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$HERE/../村通/03_后端开发/cuntong-server/.env"

while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in
    CT_DB_URL=*|CT_DB_USER=*|CT_DB_PASSWORD=*|CT_REDIS_HOST=*|CT_REDIS_PORT=*|CT_REDIS_PASSWORD=*)
      key="${line%%=*}"
      value="${line#*=}"
      value="${value%\"}"
      value="${value#\"}"
      export "$key=$value"
      ;;
  esac
done < "$ENV_FILE"

BASE_URL="${CT_DB_URL/cuntong/baseplate_dev}"
case "$BASE_URL" in
  *\?*) SEP="&" ;;
  *) SEP="?" ;;
esac

export BASEPLATE_DB_URL="${BASE_URL}${SEP}sslmode=disable"
export BASEPLATE_DB_USER="$CT_DB_USER"
export BASEPLATE_DB_PASSWORD="$CT_DB_PASSWORD"
export BASEPLATE_REDIS_HOST="$CT_REDIS_HOST"
export BASEPLATE_REDIS_PORT="$CT_REDIS_PORT"
export BASEPLATE_REDIS_PASSWORD="$CT_REDIS_PASSWORD"
export BASEPLATE_JWT_SECRET="${BASEPLATE_JWT_SECRET:-local-dev-jwt-secret-change-me}"
# 开放平台令牌必须是独立密钥：与 JWT 同值时第三方 token 会被内部鉴权链当成用户（启动期自检会拦）
export BASEPLATE_OPEN_TOKEN_SECRET="${BASEPLATE_OPEN_TOKEN_SECRET:-local-dev-open-token-secret-change-me}"
export BASEPLATE_SECRET_KEY="${BASEPLATE_SECRET_KEY:-local-dev-only-change-me-secret-key}"
export BASEPLATE_ADMIN_PASSWORD="${BASEPLATE_ADMIN_PASSWORD:-Admin@12345}"

exec "$JAVA_HOME/bin/java" -jar "$HERE/baseplate-bootstrap/target/baseplate-bootstrap-1.0.0-SNAPSHOT.jar" \
  "${MIGRATE_ARGS[@]}" "${APP_ARGS[@]}"
