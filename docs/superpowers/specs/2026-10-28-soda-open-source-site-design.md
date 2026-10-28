# SODA 开源与产品站设计（三子工程）

- 日期：2026-10-28
- 状态：已与用户确认设计，待执行
- 范围：soda-core、soda-template、soda-template-front（+ 新建 soda-site、soda-demo）

## 背景与目标

SODA 系列（DDD/CQRS Spring Boot Starter + 项目生成器 + 生成器前端）准备开源。当前对外只有
GitHub 仓库页（star/下载），观感简陋；三个工程文档语言不统一、无 LICENSE（两个）、无 CI、
README 有损坏内容和错误徽章。

两个目标：

1. **三个工程开源**：MIT 协议，中英双语，敏感信息清零，CI 徽章齐备。
2. **产品页 + 文档站 + demo**：好看、push 自动发布、中英双语，提升门面并沉淀文档。

## 已确认的决策

| 决策点 | 结论 |
|---|---|
| 站点形态 | VitePress 一体化站（首页=产品页，/guide=文档） |
| 品牌名 | **SODA**（模块名 soda-core / soda-template 保留） |
| 默认语言 | 英文优先（根路径=EN），`/zh/` 中文版，右上角切换 |
| 仓库归属 | 个人账号 `chenkangzeng1`（与现有 soda-core 一致），不建 org |
| 域名 | 先 `chenkangzeng1.github.io/soda-site`，自定义域名二期 |
| live demo | 二期（需主机），本期只做 demo 仓库 |

## 子工程一：开源卫生（三仓库）

顺序第一，半天量级。

1. **LICENSE**：三仓库统一 MIT（soda-core 已有；soda-template、soda-template-front 新增）。
2. **README 双语规范**：`README.md`（英文，主）+ `README.zh-CN.md`（中文）。
   - soda-core：替换现有 README-CN.md 命名；修复 README 中混入的 `}` 乱码段落；
     修正 Maven Central 徽章坐标（现指向不存在的 `com.hibuka.soda:soda-core`，应为 `soda-core-base`）。
   - soda-template：现有中文 README 迁移为 README.zh-CN.md，新写英文 README.md。
   - soda-template-front：新写双语 README（现仅有 README-ENV.md，保留为内部环境文档或并入）。
3. **敏感信息扫描**（开源前必须）：内网 URL（git@buka、packages.aliyun.com 等）、账号密码、
   个人/公司邮箱、公司内部命名（buka 等）、模板内 application-prod.yaml 的内网配置。
   产出清单给用户确认后清理。**公司授权由用户自行确认（soda-template/front 目前在公司内网 git）。**
4. **GitHub 仓库**：新建公开仓库 `chenkangzeng1/soda-template`、`chenkangzeng1/soda-template-front`，
   公司内网 remote 保留为双 remote。
5. **CI**：GitHub Actions 对 soda-core 做构建+测试（JDK 21），README 挂构建徽章。
6. **磁盘清理**：删 `log.txt`、`maven-migration.log`、`*-repo-bundle.zip`、`.zip-workspace/`（均已被
   gitignore，不影响仓库内容）。
7. **顺手纳入**：soda-core 的 `deploy/`（RELEASE_SKILL.md、release.sh、release.local.sh.example）
   已完成待提交，随本次一起入库（`release.local.sh` 永不入库）。

## 子工程二：soda-site（VitePress 产品页+文档站）

新仓库 `chenkangzeng1/soda-site`，一两天量级（含内容生产）。

### 技术方案

- VitePress + 官方多语言方案：`docs/en/`（默认，根路径）+ `docs/zh/`（`/zh/` 路径）。
- GitHub Actions：push main → 构建 → 发布 GitHub Pages（`chenkangzeng1.github.io/soda-site`）。
- 内容生产：从 README-CN/README/CONTEXT_AND_SAFETY_MECHANISM/soda-template README 提炼起草，
  **英文先行、中文镜像**，用户只审核。

### 页面结构

- **首页（产品页）**：hero（"SODA — Spring Boot DDD Starter" 一句话价值主张）→ 功能卡片
  （DDD 分层 / CQRS / 事件驱动 / 统一异常 / 参数校验 / 异步线程池）→ 三分钟快速开始
  （Maven 坐标 + 最小示例）→ 架构图 → 徽章行（Maven Central / MIT / CI）。
- **文档区（/guide/）**：介绍 / 快速开始 / CQRS / 事件驱动 / 异常与校验 / 配置参考 / 更新日志。
- 文档深度：指南级（示例驱动），不含完整 Javadoc API 手册（二期可接 javadoc 生成）。

## 子工程三：soda-demo

半天量级。

- 用 soda-template 生成 `soda-simple-sb4` 示例 → 推 `chenkangzeng1/soda-demo`，钉 soda-core `4.x`
  稳定版。
- 双语 README + 运行截图（截图复用到产品页"示例"章节）。
- demo 仓库挂 CI（示例可编译 = 文档代码可信），同时作为 RELEASE_SKILL.md 发版冒烟素材。

## 明确不做（本期）

- live demo 部署（需主机，二期）
- 自定义域名（二期，随时可接）
- CoC / SECURITY.md / 完整 Javadoc 站（YAGNI）
- GitHub org 迁移

## 执行顺序

1. 子工程一（开源卫生）与子工程二（站点内容生产）可并行
2. 子工程二（站点搭建与发布）
3. 子工程三（demo）

## 验收标准

- 三仓库：MIT LICENSE 就位、双语 README 就位、敏感信息清单清零、soda-core CI 绿灯带徽章。
- soda-site：push main 自动发布；中英切换正常；首页产品页 + 指南文档可访问。
- soda-demo：仓库公开、示例可编译运行、双语 README。

## 风险

- **授权风险**：soda-template/front 开源需公司层面确认（用户负责）。
- **敏感信息遗漏**：扫描后仍可能有漏网（如 git 历史）；对策：只清理工作区 + 提醒用户核查历史，
  必要时新仓库用 fresh history 推送（不带旧历史，天然规避历史泄漏）。
