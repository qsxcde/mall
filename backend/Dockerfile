# 极客数码商城 - 后端镜像
#
# 先在 backend 目录构建产物：mvn -DskipTests clean package
# 再在 backend 目录构建镜像：docker build -t geek-mall-server:1.0.0 .
#
# 如需在镜像内完成构建，可改用下面的多阶段写法（会显著拉长构建时间）。
FROM eclipse-temurin:21-jre

LABEL maintainer="geek-mall" \
      description="极客数码商城后端服务"

WORKDIR /app

ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -Duser.timezone=Asia/Shanghai"

# curl 用于容器健康检查；同时创建非 root 运行用户与可写目录（日志 / 本地上传降级）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app \
    && useradd -r -g app app \
    && mkdir -p /app/logs /app/uploads \
    && chown -R app:app /app

COPY --chown=app:app target/*.jar app.jar

USER app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/actuator/health | grep -q UP || exit 1"]

# 用 sh -c 保证 $JAVA_OPTS 被展开
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
