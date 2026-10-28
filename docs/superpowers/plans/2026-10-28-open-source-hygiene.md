# Open-Source Hygiene Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 soda-core、soda-template、soda-template-front 三个仓库整理到可开源状态：MIT 协议、双语 README、敏感信息清零、CI 就位，并推送两个新公开仓库到 GitHub。

**Architecture:** 纯文档/配置工程，不改业务代码。按"扫描→清理→补文件→CI→推送"顺序推进；扫描结果需用户确认后才能执行清理与推送。涉及三个 git 仓库，路径均在 `/Users/kangzengchen/source/buka/buka-works/soda/` 下（下文以 `soda-core`、`soda-template`、`soda-template-front` 简称）。

**Tech Stack:** git、GitHub Actions、gh CLI、JUnit（已有测试无需外部依赖）。

**全局事实（执行前已核实）：**
- Maven Central 已存在 `com.hibuka.soda` 下的 `soda-core`、`soda-core-base`、`soda-core-parent`、`soda-event-redis`。
- soda-core 测试为纯 JUnit+Mockito（`soda-core-base/src/test/java/com/hibuka/soda/{BusFacadeTest,SimpleEventBusTest,context/ContextPropagationTest}.java`），无需 Redis/容器，`mvn clean verify` 可在 CI 全绿。
- soda-core README 干净（此前误报的 `}` 乱码是终端显示问题）。
- gh CLI 已登录账号为 **hibuka-labs**，而 soda-core 的 remote 是 `git@github.com:chenkangzeng1/soda-core.git`。若 `gh repo create chenkangzeng1/...` 因权限失败，按 Task 8 的兜底流程走。
- 三个工程的品牌名对外统一为 **SODA**。

---

### Task 1: 提交 soda-core 待入库的发布工具

**Files:**
- Modify:（git 索引）`deploy/release.sh`、`deploy/release.local.sh.example`、`deploy/RELEASE_SKILL.md`、`.gitignore`

**背景：** 这些文件已写好并通过验证（dry-run 全链路、分支守卫、verify 重试），用户已在设计文档中批准入库。`deploy/release.local.sh`（密钥）绝不入库。

- [ ] **Step 1: 确认状态**

Run: `git status --short`
Expected: 包含 `?? deploy/`、`M .gitignore`（另有 `docs/superpowers/specs/…` 已提交无需处理）

- [ ] **Step 2: 确认密钥文件不在暂存区**

Run: `git check-ignore deploy/release.local.sh && echo SAFE`
Expected: 输出 `deploy/release.local.sh` 与 `SAFE`（不存在该文件也没关系，check-ignore 只查规则）

- [ ] **Step 3: 提交**

```bash
git add .gitignore deploy/release.sh deploy/release.local.sh.example deploy/RELEASE_SKILL.md
git commit -m "chore(deploy): add release script and release handbook

- release.sh: publish to Aliyun + Maven Central, 3-layer verification, branch guards
- RELEASE_SKILL.md: release runbook (version matrix, backport mapping, checklists)
- release.local.sh.example: secrets template (real file is gitignored)"
```

---

### Task 2: 磁盘垃圾清理（三仓库）

**Files:** 无入库变更（只删被 gitignore 的本地垃圾）

- [ ] **Step 1: 清理 soda-core**

```bash
cd /Users/kangzengchen/source/buka/buka-works/soda/soda-core
rm -f log.txt maven-migration.log *-repo-bundle.zip
rm -rf .zip-workspace
git status --short   # 只应出现 "D prepare-maven-central-zip.bat"（Step 3 提交）
git rm prepare-maven-central-zip.bat   # Windows 专用变体，已无用途
```

注意：`prepare-maven-central-zip.sh` 与 `deploy.sh` **保留**（前者被 release.sh 调用；后者含密钥且被 gitignore，删除与否由用户在 Task 3 确认后决定）。

- [ ] **Step 2: 清理另两个仓库的同类垃圾**

```bash
cd /Users/kangzengchen/source/buka/buka-works/soda/soda-template
find . -maxdepth 2 -name "*.log" -o -maxdepth 2 -name "*.zip" -o -maxdepth 2 -name ".DS_Store" | grep -v node_modules
# 检查输出，逐个确认是垃圾后删除；git status 确认无入库文件被误删
cd /Users/kangzengchen/source/buka/buka-works/soda/soda-template-front
rm -f build.tar .DS_Store; rm -rf node_modules/.DS_Store
git status --short
```

- [ ] **Step 3: Commit**

Run: `git -C /Users/kangzengchen/source/buka/buka-works/soda/soda-core status --short`
Expected: 仅 `D prepare-maven-central-zip.bat` → 提交；另两个仓库的 status 为空则无需提交。

```bash
git -C /Users/kangzengchen/source/buka/buka-works/soda/soda-core commit -am "chore: remove unused windows zip-prep script and local build junk"
```

---

### Task 3: 敏感信息扫描（三仓库）→ 用户确认

**Files:**
- Create: `/Users/kangzengchen/source/buka/buka-works/soda/OPEN-SOURCE-SCAN.md`（**仓库目录之外**，永不入库）

- [ ] **Step 1: 工作区扫描（只输出文件名，不输出内容，避免密钥进对话记录）**

对每个仓库执行（把 `$REPO` 分别设为三个仓库路径）：

```bash
REPO=/Users/kangzengchen/source/buka/buka-works/soda/soda-core   # 换成目标仓库
cd "$REPO"
grep -rniE 'password|passwd|secret|token|aliyun|oss-cn|buka|163\.com|qq\.com|internal|内网|密码|私有仓库' \
  --include='*.java' --include='*.xml' --include='*.yml' --include='*.yaml' \
  --include='*.md' --include='*.sh' --include='*.json' --include='*.properties' --include='*.ts' --include='*.tsx' --include='*.js' \
  . 2>/dev/null | grep -v node_modules | cut -d: -f1 | sort -u
```

- [ ] **Step 2: 历史扫描（commit+文件名，不输出内容）**

```bash
cd "$REPO"
git grep -l -iE 'password|passwd|secret|token|aliyun' $(git rev-list --all) 2>/dev/null | sed 's/^[0-9a-f]*://' | sort -u
```

- [ ] **Step 3: 写扫描报告**

把结果整理进 `/Users/kangzengchen/source/buka/buka-works/soda/OPEN-SOURCE-SCAN.md`，格式：

```markdown
# 开源敏感信息扫描报告（2026-10-28）
## soda-core
### 工作区命中
- <文件>: <命中类型>（如 password/内网 URL/邮箱）→ 建议处置：<脱敏/删除/保留并说明>
### 历史命中
- <文件>: <所在 commit 范围> → 建议处置：<新仓库 fresh history / 保留>
## soda-template
（同上）
## soda-template-front
（同上，重点查 README-ENV.md 中"云效流水线""API 认证"节是否含公司流水线地址/密钥）
```

- [ ] **Step 4: 暂停，把报告摘要交给用户确认**

**执行到这里必须停**，等用户对每条命中的处置意见。确认后的处置动作并入 Task 4（soda-core）、Task 6（soda-template）、Task 7（front）执行。若用户决定旧 git 历史不清理，则 Task 8/9 的 fresh-history 推送天然规避历史泄漏。

---

### Task 4: soda-core README 对齐现状 + 中文 README 改名

**Files:**
- Modify: `README.md`
- Rename: `README-CN.md` → `README.zh-CN.md`
- Modify: `README.zh-CN.md`
- Modify: `docs/superpowers/specs/2026-10-28-soda-open-source-site-design.md`（补记扫描处置结论一行，仅当 Task 3 有影响 README 的发现）

- [ ] **Step 1: 改名并修正互链**

```bash
cd /Users/kangzengchen/source/buka/buka-works/soda/soda-core
git mv README-CN.md README.zh-CN.md
```

两个文件里的语言切换行（`README.md:3` 与 `README.zh-CN.md:3`）统一替换为：

```markdown
[English](README.md) | [中文文档](README.zh-CN.md)
```

- [ ] **Step 2: README.md 徽章块替换**

将 `README.md` 第 5-7 行的徽章块整体替换为：

```markdown
[![CI](https://github.com/chenkangzeng1/soda-core/actions/workflows/ci.yml/badge.svg)](https://github.com/chenkangzeng1/soda-core/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.hibuka.soda/soda-core-base.svg)](https://search.maven.org/artifact/com.hibuka.soda/soda-core-base)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://www.oracle.com/java/)
```

`README.zh-CN.md` 的徽章块（第 5-7 行）做相同替换。

- [ ] **Step 3: README.md「## Requirements」整节替换**

```markdown
## Requirements

- Java 17 or higher (recommended 21)
- Spring Boot 4.1.x (Jackson 3). For Spring Boot 3.x projects, use the frozen `2.1.2-boot32` line; for Boot 2.x, use `2.1.0` — see the version matrix in the release handbook. New work happens on the Boot 4 line only.
- Maven 3.6+ or Gradle 6+
```

`README.zh-CN.md` 的「## 环境要求」节（若标题不同，定位到描述 Java/Spring Boot 版本要求的节）替换为对应的中文：

```markdown
## 环境要求

- Java 17 及以上（推荐 21）
- Spring Boot 4.1.x（Jackson 3）。Spring Boot 3.x 项目请使用冻结的 `2.1.2-boot32` 线，Boot 2.x 使用 `2.1.0` —— 版本矩阵见发布手册。新功能只在 Boot 4 线开发。
- Maven 3.6+ 或 Gradle 6+
```

- [ ] **Step 4: README.md「## Build and Compilation」整节替换（多 POM 复制流程已废弃）**

```markdown
## Build and Compilation

```bash
mvn clean verify          # build + tests
mvn clean package -DskipTests
```

One `pom.xml` per maintenance branch — there are no per-baseline POM copies. The Boot 4 line lives on `main`; frozen baselines live on `maintenance/*` branches.
```

`README.zh-CN.md` 对应的「## 构建」节替换为：

```markdown
## 构建

```bash
mvn clean verify          # 构建 + 测试
mvn clean package -DskipTests
```

每个维护分支只有一份 `pom.xml`，不再按基线复制 POM。Boot 4 线在 `main`，冻结基线在 `maintenance/*` 分支。
```

- [ ] **Step 5: 修正文档示例里的版本号**

在两个 README 中全局搜索 `2.0.0`（安装示例坐标），替换为 `4.0.0`；搜索 `artifactId>soda-core<`（示例依赖），若存在则按现模块坐标改为 `soda-core-base`（或补上 `soda-event-redis` 示例）。

Run: `grep -n "2.0.0\|artifactId" README.md | head`
Expected: 示例坐标为 `com.hibuka.soda:soda-core-base:4.0.0`

- [ ] **Step 6: 应用 Task 3 确认的 soda-core 处置项**

按用户确认的扫描报告逐条执行（脱敏/删除），本步骤是空操作则跳过。

- [ ] **Step 7: 验证链接**

Run: `grep -rn "README-CN" README.md README.zh-CN.md`
Expected: 无输出（旧文件名引用已清零）

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "docs: align READMEs with current baselines, rename zh doc

- README-CN.md -> README.zh-CN.md, fix cross links
- badges: CI + soda-core-base on Maven Central + Java 17+
- requirements/build sections match the single-pom baseline policy"
```

---

### Task 5: soda-core GitHub Actions CI + CI 徽章

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: 本地先跑一遍确认绿**

Run: `mvn -B clean verify`
Expected: `BUILD SUCCESS`（含 3 个测试类通过）

- [ ] **Step 2: 创建 workflow**

```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: [main, "maintenance/**"]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - name: Build and test
        run: mvn -B clean verify
```

- [ ] **Step 3: Commit 并推送触发真实 CI**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add GitHub Actions build and test workflow"
git push origin main
```

- [ ] **Step 4: 验证 CI 运行**

Run: `gh run list --repo chenkangzeng1/soda-core --limit 3`
Expected: 最新 run 状态最终为 `success`（徽章 Task 4 已加，绿了会自动变亮）

---

### Task 6: soda-template 开源文件（LICENSE + 双语 README）

**Files:**
- Create: `LICENSE`
- Rename: `README.md` → `README.zh-CN.md`
- Create: `README.md`（英文）
- Modify: 清理公司内容（按 Task 3 确认结论，重点：`src/main/resources/application-prod.yaml`、`deploy.sh`）

- [ ] **Step 1: 放 LICENSE（MIT 全文）**

```bash
cp /Users/kangzengchen/source/buka/buka-works/soda/soda-core/LICENSE \
   /Users/kangzengchen/source/buka/buka-works/soda/soda-template/LICENSE
```

Run: `head -1 /Users/kangzengchen/source/buka/buka-works/soda/soda-template/LICENSE`
Expected: `MIT License`

- [ ] **Step 2: 现中文 README 迁移为 zh-CN**

```bash
cd /Users/kangzengchen/source/buka/buka-works/soda/soda-template
git mv README.md README.zh-CN.md
```

在 `README.zh-CN.md` 顶部（标题行之后）插入语言切换行：

```markdown
[English](README.md) | [中文文档](README.zh-CN.md)
```

- [ ] **Step 3: 创建英文 README.md（内容完整如下）**

````markdown
# SODA Template

[English](README.md) | [中文文档](README.zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Project generator for the **SODA** architecture — a Spring Boot starter kit for Domain-Driven Design (DDD) with CQRS and event-driven design. Generate a production-shaped microservice skeleton in minutes. (中文文档见 [README.zh-CN.md](README.zh-CN.md)，含完整架构详解。)

## What is SODA?

SODA (Simple Oriented Domain Architecture) packages enterprise practices — layered DDD, CQRS command/query buses, domain events, unified exception handling, parameter validation — into reusable starters ([soda-core](https://github.com/chenkangzeng1/soda-core)) and this generator.

## Features

- **Template-based generation**: pick a template, fill in project/package names, get a buildable skeleton
- **Spring Boot baseline selection**: templates carry their own Boot/JDK baseline (Boot 4 line is the active one; Boot 3.x is frozen)
- **SODA architecture built in**: base layer, CQRS facade (BusFacade), domain events, error model
- **Single-app and cloud layouts**: `soda-simple` for monoliths, `scoda-cloud` for Spring Cloud multi-module

## Templates

| id | shape | baseline | description |
|---|---|---|---|
| `soda-simple` | single app | Boot 2/3 line | DDD + CQRS single-application example |
| `scoda-cloud` | multi-module | Boot 2/3 line | Spring Cloud multi-module solution |
| `soda-simple-sb4` | single app | Boot 4 / Java 21 | SODA simple on the active Boot 4 line |
| `scoda-cloud-sb4` | multi-module | Boot 4 / Java 21 | SODA cloud on the active Boot 4 line |

Templates live under `src/main/resources/templates/`; enabled templates are listed in `src/main/resources/template-config.json`.

## Requirements

- Java 17+ (21 for the Boot 4 line)
- Maven 3.6+

## Quick Start

```bash
git clone https://github.com/chenkangzeng1/soda-template.git
cd soda-template
mvn clean package -DskipTests
java -jar target/*.jar
```

The service listens on port `7001` (see `src/main/resources/application.yaml`). The web UI is [soda-template-front](https://github.com/chenkangzeng1/soda-template-front).

Generated projects depend on [soda-core](https://github.com/chenkangzeng1/soda-core) artifacts published to Maven Central (`com.hibuka.soda:soda-core-base`, `com.hibuka.soda:soda-event-redis`).

## Project Layout

```
src/main/resources/
├── templates/           # project templates (one directory per template)
│   ├── soda-simple/
│   ├── soda-simple-sb4/
│   ├── scoda-cloud/
│   └── scoda-cloud-sb4/
├── template-config.json # enabled templates shown in the UI
└── application*.yaml
```

## License

[MIT](LICENSE)
````

- [ ] **Step 4: 按 Task 3 确认结论清理公司内容**

重点：`src/main/resources/application-prod.yaml`（内网地址→占位符或删除）、`deploy.sh`（含私有仓库地址则 gitignore 或脱敏）。逐条按用户确认执行。

- [ ] **Step 5: 验证**

Run: `ls LICENSE README.md README.zh-CN.md && grep -c "README.zh-CN" README.md`
Expected: 三个文件都在；grep 输出 ≥ 1

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: prepare for open source (MIT license, bilingual README)

- add MIT LICENSE
- README.md -> README.zh-CN.md, new English README.md
- scrub internal configuration per security scan"
```

---

### Task 7: soda-template-front 开源文件（LICENSE + 双语 README）

**Files:**
- Create: `LICENSE`、`README.md`、`README.zh-CN.md`
- Modify: `README-ENV.md`（脱敏，见 Step 3）

- [ ] **Step 1: 放 LICENSE**

```bash
cp /Users/kangzengchen/source/buka/buka-works/soda/soda-core/LICENSE \
   /Users/kangzengchen/source/buka/buka-works/soda/soda-template-front/LICENSE
```

- [ ] **Step 2: 创建 README.md（内容完整如下）**

````markdown
# SODA Template Front

[English](README.md) | [中文文档](README.zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Web UI for the [soda-template](https://github.com/chenkangzeng1/soda-template) project generator: pick a SODA template, choose a Spring Boot baseline, enter project/package names, and generate a buildable skeleton.

## Features

- Template browser with details (categories, features, modules)
- Dynamic Spring Boot version selector driven by generator config
- HMAC signed API authentication
- Chinese / English interface

## Quick Start

```bash
yarn install
yarn start        # dev server with proxy to generator on :7001
```

Requires Node.js 18+ and Yarn 1.x. The dev server proxies API calls to `http://localhost:7001` (see `package.json` `proxy`).

## Build

```bash
yarn build        # production build into build/
```

Environment configuration (API base URL, auth keys) is documented in [README-ENV.md](README-ENV.md) — the file ships with placeholders only; never commit real keys.

## License

[MIT](LICENSE)
````

- [ ] **Step 3: 创建 README.zh-CN.md（内容完整如下）**

````markdown
# SODA Template Front

[English](README.md) | [中文文档](README.zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

[soda-template](https://github.com/chenkangzeng1/soda-template) 项目生成器的 Web 界面：选择 SODA 模板、选择 Spring Boot 基线、填写项目/包名，即可生成可构建的工程骨架。

## 功能特性

- 模板浏览（分类、特性、模块说明）
- 由生成器配置驱动的 Spring Boot 版本动态选择器
- HMAC 签名 API 认证
- 中英文界面

## 快速开始

```bash
yarn install
yarn start        # 开发服务器，代理到 :7001 的生成器服务
```

需要 Node.js 18+ 与 Yarn 1.x。开发服务器将 API 代理到 `http://localhost:7001`（见 `package.json` 的 `proxy`）。

## 构建

```bash
yarn build        # 生产构建，输出到 build/
```

环境配置（API 地址、认证密钥）见 [README-ENV.md](README-ENV.md)——该文件只含占位符，切勿提交真实密钥。

## 许可证

[MIT](LICENSE)
````

- [ ] **Step 4: README-ENV.md 脱敏**

删除/泛化其中的公司专有内容（"云效流水线"整节删除、真实 API 地址与密钥换占位符如 `<API_BASE_URL>`、`<HMAC_KEY>`），保留"配置文件说明/构建命令/认证机制"三节的通用内容。

- [ ] **Step 5: 应用 Task 3 确认的 front 处置项并提交**

```bash
git add -A
git commit -m "docs: prepare for open source (MIT license, bilingual README)

- add MIT LICENSE and bilingual README
- sanitize README-ENV.md (remove CI pipeline details, placeholder-ize secrets)"
```

---

### Task 8: soda-template 推送 GitHub（fresh history + 双 remote）

**Files:** 无文件变更（git 操作）

- [ ] **Step 1: 建公开仓库**

```bash
gh repo create chenkangzeng1/soda-template --public \
  --description "Project generator for the SODA architecture - Spring Boot DDD/CQRS starter skeletons"
```

Expected: 创建成功。若报权限错误（gh 登录账号是 hibuka-labs），**兜底**：改用 `gh repo create hibuka-labs/soda-template --public ...`（若 hibuka-labs 是你的 org），或让用户在网页手动建空仓库（不要勾选 README）后继续。

- [ ] **Step 2: 用 fresh history 推送（规避旧历史泄漏）**

```bash
cd /Users/kangzengchen/source/buka/buka-works/soda/soda-template
git checkout --orphan public-main
git add -A
git commit -m "chore: initial public release of soda-template"
git remote add github git@github.com:chenkangzeng1/soda-template.git
git push github public-main:main
git checkout -          # 回到原分支
git branch -D public-main
```

Remote 布局：`origin` 保持公司内网仓库不动，`github` 为公开仓库。

- [ ] **Step 3: 验证公开仓库**

Run: `gh repo view chenkangzeng1/soda-template --json visibility,url`
Expected: `visibility: PUBLIC`；浏览器打开 URL 能看到 README 英文版。

---

### Task 9: soda-template-front 推送 GitHub（同 Task 8 流程）

**Files:** 无文件变更（git 操作）

- [ ] **Step 1: 建公开仓库**

```bash
gh repo create chenkangzeng1/soda-template-front --public \
  --description "Web UI for the SODA project generator - template picker and Spring Boot baseline selector"
```

（兜底同 Task 8 Step 1。）

- [ ] **Step 2: fresh history 推送**

```bash
cd /Users/kangzengchen/source/buka/buka-works/soda/soda-template-front
git checkout --orphan public-main
git add -A
git commit -m "chore: initial public release of soda-template-front"
git remote add github git@github.com:chenkangzeng1/soda-template-front.git
git push github public-main:main
git checkout -
git branch -D public-main
```

- [ ] **Step 3: 验证**

Run: `gh repo view chenkangzeng1/soda-template-front --json visibility`
Expected: `PUBLIC`

---

### Task 10: 终验

- [ ] **Step 1: 三仓库入库状态干净**

```bash
for r in soda-core soda-template soda-template-front; do
  echo "== $r"; git -C /Users/kangzengchen/source/buka/buka-works/soda/$r status --short
done
```
Expected: 全部空输出

- [ ] **Step 2: README 互链与徽章抽查**

```bash
grep -L "MIT" /Users/kangzengchen/source/buka/buka-works/soda/*/LICENSE   # 应无输出
grep -rn "README-CN\b" /Users/kangzengchen/source/buka/buka-works/soda/*/README*.md   # 应无输出
```

- [ ] **Step 3: 公开可见性与 CI**

Run: `gh run list --repo chenkangzeng1/soda-core --limit 1`
Expected: 状态 `success`

- [ ] **Step 4: 汇报用户**

输出总结：三仓库状态、扫描处置结果、新仓库 URL、遗留事项（如历史未清理则已由 fresh history 规避）。

---

## 已知限制（有意为之）

- soda-template 的英文 README 是精简版（中文 README.zh-CN.md 是全文档）；深度英文文档由后续 soda-site 计划（docs/en/ 指南）承担，不在本计划强行全译。
- 旧 git 历史默认不重写；两个新公开仓库用 fresh-history 推送规避历史泄漏，soda-core 已在公开仓库则以工作区清理为准。
