# InfraHub backend - multi-stage build
# Build : Maven 3.9 + Temurin JDK 17 (multi-module reactor, -pl infrahub-web -am)
# Run   : Temurin JRE 17, non-root user, spring-boot fat jar
#
# 运行期配置：
#   - 本镜像**不含本地 application.yml**。该文件未被版本控制、内容含真实数据库连接与密钥，
#     由仓库根 .dockerignore 排除在构建上下文之外，下面的 RUN 守卫会在它意外出现时直接让构建失败。
#   - 镜像内仍有代码自带的应用资源（例如 logback-spring.xml），因此不能笼统地说"没有任何配置文件"；
#     准确说法是：**不含任何本地运行期配置**。数据源、JWT、Flyway 策略等运行期配置全部由
#     **环境变量**提供（见 docker-compose.infrahub-test.yml），未提供的项使用 Spring Boot /
#     各框架的内置默认值。
#   - 上述内容是构建设计与 .dockerignore 规则，本轮没有实际构建镜像或扫描制品，因此不是"已验证"。

# ---------- Stage 1: Build ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 1) POM layer first so dependency resolution is cached across source rebuilds.
COPY pom.xml .
COPY infrahub-dependencies/pom.xml infrahub-dependencies/pom.xml
COPY infrahub-common/pom.xml infrahub-common/pom.xml
COPY infrahub-infra/pom.xml infrahub-infra/pom.xml
COPY infrahub-system/pom.xml infrahub-system/pom.xml
COPY infrahub-auth/pom.xml infrahub-auth/pom.xml
COPY infrahub-base/pom.xml infrahub-base/pom.xml
COPY infrahub-pipeline/pom.xml infrahub-pipeline/pom.xml
COPY infrahub-release/pom.xml infrahub-release/pom.xml
COPY infrahub-web/pom.xml infrahub-web/pom.xml

# Best-effort dependency cache (go-offline cannot reach every plugin goal;
# the real build re-resolves anything missing online).
RUN mvn -B -q -o dependency:go-offline -pl infrahub-web -am -DskipTests || true

# 2) Source layer, then package the web module with its module dependencies.
COPY infrahub-common infrahub-common
COPY infrahub-infra infrahub-infra
COPY infrahub-system infrahub-system
COPY infrahub-auth infrahub-auth
COPY infrahub-base infrahub-base
COPY infrahub-pipeline infrahub-pipeline
COPY infrahub-release infrahub-release
COPY infrahub-web infrahub-web

# 防御性检查：application.yml 含真实凭据，必须已被 .dockerignore 排除。
# 若将来有人删掉那条忽略规则，这里让构建失败，而不是把凭据打进镜像。
RUN if [ -e infrahub-web/src/main/resources/application.yml ]; then \
        echo "ERROR: infrahub-web/src/main/resources/application.yml is in the build context." >&2; \
        echo "       It contains real credentials and must be excluded via .dockerignore." >&2; \
        exit 1; \
    fi

# 迁移资源：单一的版本受控来源是仓库根的 db/flyway。
#   db/flyway/sql                              → infrahub-web/pom.xml 映射为 classpath:db/migration
#   db/flyway/legacy-missing-migration-allowlist.txt → 映射为 classpath:db/migration-policy
# 只复制这两项；db/ 的其余内容（bootstrap、cleanup、验收与测试数据）不在构建上下文里。
# 缺了它们 Maven 会静默跳过资源映射，打出的 jar 不含任何迁移脚本 —— 那种镜像只会在启动时被
# LegacyMissingMigrationGate 拒绝，或被 SPRING_FLYWAY_ENABLED=false 掩盖。
COPY db/flyway/sql db/flyway/sql
COPY db/flyway/legacy-missing-migration-allowlist.txt db/flyway/legacy-missing-migration-allowlist.txt

RUN mvn -B -pl infrahub-web -am package -DskipTests

# ---------- Stage 2: Run ----------
FROM eclipse-temurin:17-jre-jammy

# curl for the container health check against /actuator/health.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system infrahub \
    && useradd --system --gid infrahub --home-dir /app --shell /usr/sbin/nologin infrahub

WORKDIR /app

COPY --from=builder /build/infrahub-web/target/infrahub-web-1.0.0-SNAPSHOT.jar /app/app.jar

USER infrahub

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
