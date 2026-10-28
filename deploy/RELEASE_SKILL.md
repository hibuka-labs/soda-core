# Release Skill — soda 系列发布手册

> 使用方式：发布时把本文件拖进对话，说"发布 x.y.z"，AI 按本手册执行。
> 本手册自包含，不依赖其他上下文。踩坑后把教训补进文末「实坑记录」。

**涉及工程（三个平级 git 仓库）：**

| 工程 | 路径 | 角色 |
|---|---|---|
| soda-core | `/Users/kangzengchen/source/buka/buka-works/soda/soda-core` | 发布主体（jar：soda-core-base、soda-event-redis） |
| soda-template | `/Users/kangzengchen/source/buka/buka-works/soda/soda-template` | 项目生成器，发布后更新模板里的 `soda.version` pin |
| soda-template-front | `/Users/kangzengchen/source/buka/buka-works/soda/soda-template-front` | 生成器前端，一般不随 jar 发版变动 |

---

## 一句话流程

```
发布前检查 → main 上出版本（脚本：构建/发布/验证/打 tag）
           → AI 移植到老线分支并发布（best-effort）
           → 更新 soda-template 模板 pin + README 版本矩阵
```

---

## 版本矩阵（唯一事实来源）

| Boot 基线 | Jackson | Java | soda-core 版本线 | 分支 | 状态 |
|---|---|---|---|---|---|
| 4.1.x | 3.x | 17+（建议 21） | `4.x`（当前活跃） | `main` | ✅ 活跃，新功能只在这 |
| 3.2.x | 2.x | 8+ | `3.x`（前身 `2.1.2-boot32`） | `maintenance/3.x`（待从 `v2.1.2-boot32` 切出） | 🔒 冻结，应急修复 |
| 2.3.x ~ 2.7.x | 2.x | 8 | `2.x`（前身 `2.0.0`/`2.1.0` 系列） | 不建分支 | ⛔ EOL，老 jar 留存即可 |

**版本命名规则：主版本 = Boot 基线代次**，线内 minor/patch 正常语义化（minor=新特性，patch=修复）。
历史遗留版本（`2.1.2-boot32`、`17.0` 等）已发布不回收，规则从下一个版本起生效。

⚠️ 当前 `pom.xml` 里版本是历史遗留的 `17.0`，**下一个版本必须换成符合矩阵的 `4.x.y`**（脚本 `versions:set` 会自动改，但发布前检查清单要确认）。

---

## 发布前检查清单

- [ ] `mvn clean verify` 本地全绿（测试不过就别发）
- [ ] 版本号符合矩阵：x.y.z、大版本与分支匹配（4→main / 3→maintenance/3.x）
- [ ] 本轮版本号未与已发布冲突（阿里云 + Central 各搜一下）
- [ ] `deploy/release.local.sh` 已配置（从 `release.local.sh.example` 复制）
- [ ] README / README-CN 的安装示例版本号和兼容矩阵需要更新吗？
- [ ] 有用户可见变更 → 写 CHANGELOG 条目
- [ ] 破坏性变更？→ 是否该攒到基线升级（**主线永不为兼容妥协**，老线移植不了的就跳过并记录）
- [ ] git 工作区干净、在正确的分支上

---

## 模式一：全自动发布（默认）

**执行者：AI 照本节跑；人只看结果。**

### 1. 发布主线（4.x，main）

```bash
cd /Users/kangzengchen/source/buka/buka-works/soda/soda-core
./deploy/release.sh 4.x.y                      # 默认 --target all
# ./deploy/release.sh 4.x.y --target central   # 只发 Central
# ./deploy/release.sh 4.x.y --skip-tests       # 测试已单独跑过时
# ./deploy/release.sh 4.x.y --dry-run          # 演练，不真发
```

脚本内部顺序（出问题时按此排查）：
1. **守卫**：分支↔大版本匹配、版本格式、工作区干净
2. **versions:set** 把全模块版本改成目标版本
3. **mvn clean verify** 全量构建+测试
4. **阿里云**：`mvn deploy -DaltDeploymentRepository`（临时 settings.xml 注入密钥，用完即删）
5. **Maven Central**：`prepare-maven-central-zip.sh` 生成 GPG 签名 bundle → 合并为单个 deployment → `central.sonatype.com` Publisher API 上传（AUTOMATIC）→ 轮询至 PUBLISHED
6. **验证**：从远端拉回三个 artifact 的 pom（阿里云带认证、Central 走 repo1.maven.org，同步有延迟会自动重试）
7. **打 tag** `v<version>` 并推送

### 2. 移植老线（best-effort，见下方「老线 AI 移植流程」）

### 3. 发布后

- [ ] soda-template 各模板的 `soda.version` pin 更新（`src/main/resources/templates/*/pom.xml`）
- [ ] README.md / README-CN.md 兼容矩阵与安装示例
- [ ] 本轮摘要发给用户：每条线发了什么、跳过了什么、为什么

---

## 模式二：手动发布 + AI 验证

人照旧手动发（或用 `./deploy/release.sh bundle 4.x.y` 只生成签名包手动上传 Portal），
发完让 AI 跑：

```bash
./deploy/release.sh verify 4.x.y               # 三层验证，只验不发
./deploy/release.sh verify 4.x.y --target central
```

---

## 验证（三层，缺一不可）

| 层 | 手段 | 证明什么 |
|---|---|---|
| 回执 | deploy/上传 API 的返回码与状态 | 仓库收下了 |
| 拉取 | 从**远端**强制拉回 pom（`verify` 子命令） | 用户真的能解析到（Central 同步延迟约 10~30 分钟，脚本会重试） |
| 冒烟 | 用 soda-template 生成钉住新版本的示例工程，`mvn package` 启动 | 端到端能用 |

发 Central 后 repo1 短暂 404 是正常的（同步中）；Portal 状态 PUBLISHED 但超过 30 分钟仍拉不到 → 记实坑并排查。

---

## 老线 AI 移植流程（主线 → maintenance 分支）

**原则：主线永不为兼容妥协；老线 best-effort，移植不了的特性明确跳过。**

1. `git checkout maintenance/3.x && git merge main`（或 cherry-pick 本轮 commits）
2. 按映射表机械转换，修编译错误
3. 跑 `mvn clean verify` + 冒烟（生成 Boot 3 示例工程）
4. **过不了验证门就不发那条线**，绝不硬发
5. 过了就 `./deploy/release.sh 3.x.y`（在 maintenance 分支上）
6. 摘要里记录：移植了什么、跳过了什么（如 "3.x 不支持 X，需要 Java 17 语法"）

### 基线差异映射表（移植时对照；踩到新差异就补一行）

| 方向 | 差异 |
|---|---|
| 任意 → Boot 3/4 | `javax.*` → `jakarta.*` |
| Boot 2.6 及以下 ↔ 2.7+/3 | 自动配置注册：`spring.factories` ↔ `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Jackson 2 → 3 | `JsonSerializer` → `ValueSerializer`；`BeanSerializerModifier` → `ValueSerializerModifier` |
| Jackson 2 → 3 | `ObjectMapper.configure()` → `JsonMapper.builder().enable()/disable()`；`addMixIn()`/`activateDefaultTyping()` 移到 builder（后者配 `BasicPolymorphicTypeValidator`） |
| Jackson 2 → 3 | `ObjectMapper.DefaultTyping` → 顶层 `DefaultTyping`；移除 `JavaTimeModule`（3 内置） |
| Boot 3 → 4 | `spring-boot-starter-aop` → `spring-boot-starter-aspectj` |
| Boot 3 → 4 | `@AutoConfigureAfter(Xxx.class)` → 字符串引用（SB4 模块化后类引用不可用） |
| Java 17/21 → 8 | 不能用 var/record/text block/sealed 等，移植时降级语法 |

---

## 密钥与 git 忽略约定

| 文件 | 入库？ | 内容 |
|---|---|---|
| `deploy/release.sh` | ✅ | 纯逻辑，零密钥 |
| `deploy/release.local.sh` | 🚫 gitignore | 阿里云 URL/账号密码、Central Token |
| `deploy/release.local.sh.example` | ✅ | 占位示例 |
| `deploy/RELEASE_SKILL.md` | ✅ | 本手册 |

**约定：任何含密钥的脚本/配置一律 gitignore；入库文件零明文密钥。** 旧 `deploy.sh` 含阿里云明文密码（已 gitignore、未跟踪），建议尽快轮换密码后删除。

---

## 目录速查

```
soda-core/
├── deploy/
│   ├── RELEASE_SKILL.md          # 本手册
│   ├── release.sh                # 发布脚本（入库）
│   ├── release.local.sh          # 密钥（gitignore，自行从 example 复制）
│   └── release.local.sh.example
├── soda-core-base/               # 模块 1（先于 event-redis 构建，reactor 自动排序）
├── soda-event-redis/             # 模块 2（依赖 base 的 ${project.version}）
├── prepare-maven-central-zip.sh  # 签名 bundle 打包（release.sh 会调用）
└── pom.xml
```

根目录的 `*-repo-bundle.zip`、`.zip-workspace/`、`log.txt`、`maven-migration.log` 都是构建垃圾（已被 gitignore），可随时删。

---

## 实坑记录

> 每次发布踩坑，补一条：`YYYY-MM-DD 实坑：现象 → 根因 → 对策（脚本/清单已改哪里）`

（暂无）
