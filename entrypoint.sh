#!/bin/bash
set -e

# === 1. 将当前所有环境变量导出到文件，供 cron 任务加载 ===
# cron 执行时不会继承容器的环境变量，所以需要先持久化到文件
ENV_FILE=/app/env.sh
echo "#!/bin/bash" > "$ENV_FILE"
env | while IFS='=' read -r key value; do
  printf 'export %s=%q\n' "$key" "$value" >> "$ENV_FILE"
done

# === 2. 注册 cron 定时任务 ===
# 默认北京时间每天 9:11 执行，可通过 CRON_SCHEDULE 环境变量自定义
CRON_SCHEDULE="${CRON_SCHEDULE:-11 9 * * *}"

# 写入 cron 配置：先加载环境变量，再执行 JAR，日志输出到容器主进程的 stdout；
# 任务结束后打印与启动行同格式的 [$(date)] 行（\$(date) 在 cron 运行时展开）
echo "$CRON_SCHEDULE root . $ENV_FILE && java -jar /app/app.jar >> /proc/1/fd/1 2>&1; echo \"[\$(date)] running finished...\" >> /proc/1/fd/1" > /etc/cron.d/auto-sign-in
chmod 0644 /etc/cron.d/auto-sign-in
crontab /etc/cron.d/auto-sign-in

echo "[$(date)] cron scheduled: $CRON_SCHEDULE"

# === 3. 可选：启动时立即执行一次 ===
if [ "${RUN_ON_STARTUP:-false}" = "true" ]; then
  echo "[$(date)] running on startup..."
  java -jar /app/app.jar
  echo "[$(date)] running finished..."
fi

# === 4. 前台运行 cron 守护进程，保持容器存活 ===
echo "[$(date)] starting cron daemon..."
exec cron -f
