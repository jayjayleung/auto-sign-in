#!/bin/bash
# 启动 JAR 时加载 .env 中的环境变量
cd "$(dirname "$0")"

if [ -f .env ]; then
  set -a
  # 加载 .env：过滤空行和 # 开头的注释，然后导出
  while IFS= read -r line || [ -n "$line" ]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    export "$line"
  done < .env
  set +a
fi

# 替换为你的 JAR 文件名，或通过参数传入：./run.sh app.jar
JAR_FILE="${1:-./target/auto-sign-in-0.0.1-SNAPSHOT.jar}"
[ -n "$1" ] && shift

# cron 环境没有加载 .bashrc，PATH 里可能没有 java（如 sdkman 安装的）
if ! command -v java &>/dev/null; then
  SDKMAN_JAVA="${HOME}/.sdkman/candidates/java/current/bin/java"
  if [ -x "$SDKMAN_JAVA" ]; then
    export PATH="${HOME}/.sdkman/candidates/java/current/bin:$PATH"
  fi
fi
exec java -jar "$JAR_FILE" "$@"
