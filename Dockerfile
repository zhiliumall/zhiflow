# 智流商城后端镜像：从源码构建（两阶段），产物是单个可执行 jar。
#
#   docker build -t zhiflow/server .
#
# 构建阶段用 Maven Wrapper 拉依赖（只走 Maven Central），--mount=type=cache 让二次构建复用本地仓库。

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -DskipTests package -pl zhiflow-boot -am

FROM eclipse-temurin:25-jre
RUN groupadd --system zhiflow && useradd --system --gid zhiflow --home-dir /app zhiflow \
    && mkdir -p /app/data/uploads /app/data/home /app/logs && chown -R zhiflow:zhiflow /app
WORKDIR /app
COPY --from=build --chown=zhiflow:zhiflow /src/dist/zhiflow-boot-exec.jar /app/app.jar
USER zhiflow

ENV TZ=Asia/Shanghai \
    SERVER_PORT=8080 \
    FILE_STORAGE_DIR=/app/data/uploads \
    FILE_HOME_DIR=/app/data/home/ \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME ["/app/data"]
EXPOSE 8080

# 探活：/actuator/health 返回 "status":"UP" 才算健康（镜像里没有 curl，用 bash 的 /dev/tcp）
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT} && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
