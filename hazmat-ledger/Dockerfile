# =====================================================================
# 多阶段构建：构建镜像里带 Maven 与 JDK，运行镜像只留 JRE 和 jar。
#
# 依赖下载走 docker/maven-settings.xml 里的国内镜像；先单独拷 pom 并预热依赖，
# 这样改业务代码时不用重新下载整套依赖。
#
# 构建：
#   docker build -t hazmat-ledger:local .
# 运行（默认 H2 内存库）：
#   docker run --rm -p 8090:8090 -e HAZMAT_JWT_SECRET=<至少32字节> hazmat-ledger:local
# =====================================================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# 镜像配置只影响构建阶段，不会进运行镜像。
# 注意不能拷到 /root/.m2：下面 RUN 的 cache mount 会挂载并遮蔽该目录，
# 容器里的 Maven 就看不到 settings.xml，会绕过镜像直连 Maven Central。
COPY docker/maven-settings.xml /build/maven-settings.xml

# 依赖目录挂到 BuildKit 缓存：改代码重构建时不必重新下载。
# 没有先单独跑 dependency:go-offline——它会把 parent 的全部插件依赖都解析一遍，
# 首次构建反而更慢；一次 package 只取实际用到的。
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s /build/maven-settings.xml package -DskipTests

# =====================================================================
# 运行阶段
# =====================================================================
FROM eclipse-temurin:21-jre

# 不用 root 跑应用；被攻破时至少不能直接改容器内文件
RUN groupadd --system app && useradd --system --gid app --home-dir /app app

# curl 只用于容器的 healthcheck（探 /actuator/health）。
# 基础镜像里没有它，而用 bash /dev/tcp 手搓探测在编排里既不可读也不可靠。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app

USER app

EXPOSE 8090

# 容器内存上限交给 JVM 自己算，避免写死 -Xmx 后与 limits 打架
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
