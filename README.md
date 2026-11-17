<p align="center">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?style=for-the-badge&logo=springboot" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3-4FC08D?style=for-the-badge&logo=vue.js" alt="Vue 3">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk" alt="Java 17">
  <img src="https://img.shields.io/badge/PostgreSQL-15+-336791?style=for-the-badge&logo=postgresql" alt="PostgreSQL">
</p>

<h1 align="center">InfraHub</h1>

<p align="center">基础设施交付与运维平台 · 流水线、版本回滚、资源管理与监控告警</p>

<p align="center"><a href="https://ty-ux.github.io/InfraHub/">项目介绍与部署指南</a></p>

## 核心功能

| 模块 | 功能 |
| --- | --- |
| 流水线 | 多阶段构建与部署，实时查看运行日志 |
| 发布与回滚 | 登记制品和版本，按应用和实例回滚 |
| 资源与配置 | 管理主机、运行实例、配置分组和密钥引用 |
| 监控与治理 | 状态采集、告警通知、权限与审计 |

## 技术栈

后端使用 Java 17、Spring Boot、MyBatis-Plus 和 PostgreSQL。前端使用 Vue 3、Vite 和 Element Plus。

## 快速开始

### Docker Compose

复制 `deploy/.env.example` 为仓库根目录的 `.env`。填写 `POSTGRES_PASSWORD`、至少 32 字节的随机 `JWT_SECRET` 和首次登录用的 `INFRAHUB_AUTH_BOOTSTRAP_ADMIN_PASSWORD`，然后执行 `docker compose up -d --build`。

控制台默认监听 [localhost:8088](http://localhost:8088)。首次用 `admin` 登录后，按页面提示修改密码。

### 本地开发

准备 JDK 17、Maven、Node.js 20 和已初始化的 PostgreSQL。参考[配置模板](infrahub-web/src/main/resources/application.yml.example)在仓库外准备后端配置。数据库初始化见[迁移说明](db/flyway/README.md)。

**后端**（在仓库根目录执行，将配置路径替换为实际路径）

```bash
mvn -pl infrahub-web -am package -DskipTests
java -jar infrahub-web/target/infrahub-web-1.0.0-SNAPSHOT.jar --spring.config.additional-location=file:../application.yml
```

**前端**

```bash
cd infrahub-console
npm ci
npm run dev
```

默认控制台地址为 [localhost:5173](http://localhost:5173)，后端端口为 `8080`。

首次使用种子管理员时，在后端环境中设置 `INFRAHUB_AUTH_BOOTSTRAP_ADMIN_PASSWORD`，再用用户名 `admin` 和该密码登录。初始密码至少 15 个字符，UTF-8 编码最多 72 字节。首次登录后需修改密码，改密后即可移除该环境变量。已有正常密码的管理员可直接登录。管理员可在「系统管理 → 用户管理」创建用户和分配角色。

邮箱功能使用后端 SMTP 配置。在仓库外设置 `spring.mail.host`、`spring.mail.port`、`infrahub.auth.mail.from`，以及邮件服务要求的认证和 TLS 参数。将 `spring.mail.properties.mail.smtp.connectiontimeout`、`timeout`、`writetimeout`（同一前缀）设为有限的毫秒值。发送邀请还需设置 `infrahub.auth.mail.console-url` 为收件人可访问的 HTTPS 控制台地址，本机测试可使用 `http://localhost:5173`。

完成配置后，用户可从右上角「邮箱设置」验证并绑定邮箱，随后使用邮箱和密码或邮箱验证码登录。管理员在「系统管理 → 用户管理」发送注册邀请并预分配角色，收件人通过邮件链接设置账号和密码。

## 文档

- [Docker Compose 测试部署](docker-compose.infrahub-test.yml)
- [数据库初始化与迁移](db/flyway/README.md)
