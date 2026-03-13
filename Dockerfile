# ========== 第一阶段：Maven 编译打包 ==========
FROM maven:3.8-openjdk-8 AS builder
WORKDIR /app
# 先拷贝 pom.xml 单独下载依赖，利用 Docker 层缓存加速后续构建
COPY pom.xml .
RUN mvn dependency:go-offline -B
# 再拷贝源码进行编译打包
COPY src ./src
RUN mvn clean package -B -DskipTests

# ========== 第二阶段：运行环境 ==========
FROM eclipse-temurin:8-jdk-jammy
# 安装 cron（定时任务）、Chrome 运行所需的系统依赖库、中文字体
# Chrome 依赖大量 GUI/X11 库，基础镜像中没有，必须安装
RUN apt-get update && apt-get install -y --no-install-recommends \
    cron unzip fonts-wqy-zenhei \
    libglib2.0-0 libnss3 libatk-bridge2.0-0 libcups2 \
    libdrm2 libgbm1 libpango-1.0-0 libasound2 \
    libx11-6 libxcomposite1 libxdamage1 libxext6 \
    libxfixes3 libxrandr2 libxkbcommon0 libxshmfence1 libcairo2 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# 从构建阶段拷贝打包好的 JAR
COPY --from=builder /app/target/auto-sign-in-0.0.1-SNAPSHOT.jar ./app.jar
# 拷贝启动脚本
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
