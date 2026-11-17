# 参与 InfraHub

## 提交反馈

通过 [Issue 模板](https://github.com/ty-ux/InfraHub/issues/new/choose) 提交故障或功能建议。提交前可搜索已有 Issue，并附上版本、部署方式和相关页面。安全漏洞请按[安全政策](SECURITY.md)私密报告。

## 本地开发与验证

按 [README 的本地开发说明](README.md#本地开发)准备 JDK 17、Maven、Node.js 和 PostgreSQL。用自己的测试环境验证，配置文件和凭据保存在仓库外。

- 后端在仓库根目录执行 `mvn test`。涉及集成测试时准备可用的 Docker 环境。
- 前端在 `infrahub-console` 执行 `npm ci`、`npm test`。涉及打包时执行 `npm run build`。
- 修改部署流程时，使用测试配置验证 Docker Compose 启动和健康检查。

## 提交 Pull Request

1. 从最新的 `main` 开分支。使用自己的 Fork 即可贡献代码，有写权限的协作者也可以在 InfraHub 仓库开分支。建议用 `feat/email-login`、`fix/compose-health-check` 这类能看出用途的名字。
2. 提交前检查差异，确保其中没有真实凭据、内网地址、私有部署脚本或测试数据。
3. 向 InfraHub 的 `main` 发起 PR，写清问题、改动和测试结果，关联已有 Issue。界面改动可附脱敏截图。涉及数据库迁移、部署参数或兼容性的改动，请说明已有环境如何升级。
4. 等待 GitHub Actions 的前后端构建与测试通过，由维护者审核并合并。
