# Release Skill — soda-core 发布手册

> 使用方式：发布 soda-core 时把本文件拖进对话，说"发布 x.y.z"，AI 按本手册执行。
> 本手册只管 **soda-core 的 jar 发布**（Maven Central + 阿里云 Maven 仓库）。发布后的模板 pin 更新与生成器重新部署归 buka-template 管，见 `../../buka-template/RELEASE_SKILL.md`（两份手册互为交叉引用）。
> 本手册自包含，不依赖其他上下文。踩坑后把教训补进文末「实坑记录」。
> 执行注意：跑 release.sh 要看**退出码**——别接 `| tee` 这类管道（管道退出码是最后一个命令的，会把失败盖成成功）；要留日志就 `./deploy/release.sh x.y.z > /tmp/release.log 2>&1`。

**关联工程（不归本手册管，只留指针）：** buka-template（`../../buka-template`，FastAPI 项目生成器，部署函数计算；模板里的 `soda.version` pin 随 soda-core 发版更新，流程见其 `RELEASE_SKILL.md`）、soda-template-front（`../../soda-template-front`，生成器前端，部署 OSS，一般不随 jar 发版变动）。

---

## 一句话流程

```
发布前检查 → main 上出版本（脚本：构建/发布/验证/打 tag）
           → 更新 buka-template 模板 pin（改法与重部署见 ../../buka-template/RELEASE_SKILL.md）+ README 版本矩阵
```

---

## 版本矩阵（唯一事实来源）

| soda-core 版本线 | Java 最低 | 支持的 Spring Boot | 分支 | 状态 |
|---|---|---|---|---|
| `17.x`（当前活跃） | 17（建议 21） | 3.2.x ~ 4.1.x（**一套 jar 双线**） | `main` | ✅ 活跃，新功能只在这 |
| `4.0.0` | 21 | 4.x（单线） | — | ⛔ 历史（改名前最后版本），不再发版 |
| `2.1.x-boot32` | 8 | 3.2.x（旧线） | — | ⛔ EOL，留存即可 |
| `2.0.x` / `2.1.0` | 8 | 2.x | — | ⛔ EOL，留存即可 |

**版本命名规则：`<Java主版本>.<软件迭代>.<修复>`** —— 主版本 = 最低 Java 基线，minor = 功能迭代，patch = 修复。
一套 jar 同时支持 SB 3.2 / SB 4.x（EventSerializer 解耦，Jackson 2/3 双兼容），**消费者只选 Spring Boot 版本，soda 坐标不变**。
真断代（如 Boot 5 逼出不可兼容变更）时再裂新线，裂线差异对照文末映射表。

`17.0.1` 起为新命名规则首个版本（`17.0.0` 作废：阿里云被摇摆期试验产物占位，仓库不可覆盖/删除，见实坑）。历史版本（`2.x`、`2.1.x-boot32`、`4.0.0`、`17.0.0`）已发布不回收。

---

## 发布前检查清单

- [ ] `mvn clean verify` 本地全绿（测试不过就别发；默认流程里 release.sh 会再跑一遍）
- [ ] 版本号符合矩阵：x.y.z、大版本 ≥ 17 且在 `main` 上
- [ ] 本轮版本号未与已发布冲突（阿里云 + Central 各搜一下）→ 冲突处理规则见下
- [ ] `deploy/release.local.sh` 已配置（从 `release.local.sh.example` 复制）
- [ ] README.md / README-CN.md 的安装示例版本号和兼容矩阵需要更新吗？（动手改在发布后，见「发布后」）
- [ ] 有用户可见变更 → 写 CHANGELOG.md 条目（仓库已建 CHANGELOG.md）
- [ ] 破坏性变更？→ 是否该攒到裂线（**主线永不为兼容妥协**，双线兼容做不了的明确记入实坑）
- [ ] git 工作区干净、在 `main` 上、**已推送到 origin**（脚本只 push tag 不 push branch，本地落后的提交会只挂在 tag 上）

**冲突处理规则（两边语义不同，别混）：**

| 仓库 | 已存在同版本时 | 处理 |
|---|---|---|
| Maven Central | 版本永久占用，**不可删不可覆盖** | 必须换版本号（x.y.z+1） |
| 阿里云 | 默认禁止覆盖（deploy 返回 **409**），但内容可删可覆盖 | **先比对远端内容**（pom + jar 与本地构建 diff）：内容就是本轮要发的 → 跳过部署只做验证；内容过期/不一致 → **控制台**删该版本产物、或临时开「允许重复部署」后重发（HTTP DELETE 删不掉，API 直接回 **405**，别浪费时间试） |

比对内容这步不能省——只查"存不存在"会漏掉"存在但是旧产物"（2026-10-29 实坑：阿里云 17.0.0 是版本摇摆期的试验产物，缺 EventSerializer）。

---

## 模式一：全自动发布（默认）

**执行者：AI 照本节跑；人只看结果。**

### 1. 发布主线（17.x，main）

```bash
cd /Users/kangzengchen/source/buka/buka-works/soda/soda-core
./deploy/release.sh 17.x.y                     # 默认 --target all
# ./deploy/release.sh 17.x.y --target central   # 只发 Central
# ./deploy/release.sh 17.x.y --skip-tests       # 测试已单独跑过时
# ./deploy/release.sh 17.x.y --dry-run          # 演练，不真发
```

脚本内部顺序（出问题时按此排查）：
1. **守卫**：分支=main、大版本≥17、版本格式、工作区干净
2. **versions:set** 把全模块版本改成目标版本
3. **mvn clean verify** 全量构建+测试
4. **阿里云**：`mvn deploy -DaltDeploymentRepository`（临时 settings.xml 注入密钥，用完即删）
5. **Maven Central**：`prepare-maven-central-zip.sh` 生成 GPG 签名 bundle → 合并为单个 deployment → `central.sonatype.com` Publisher API 上传（AUTOMATIC）→ 轮询至 PUBLISHED
6. **验证**：从远端拉回三个 artifact 的 pom（阿里云带认证、Central 走 repo1.maven.org，同步有延迟会自动重试）
7. **打 tag** `v<version>` 并推送

### 2. 发布后

- [ ] buka-template 两个模板（`templates/soda-simple/pom.xml.jinja`、`templates/scoda-cloud/pom.xml.jinja`）的 `soda.version` pin 更新（统一 pin，一个版本号）——**改完 git 不等于生效**，还要按 buka-template 手册重新打包上传 function_code.zip（细节见 `../../buka-template/RELEASE_SKILL.md`「soda-core 发版联动」）
- [ ] README.md / README-CN.md 兼容矩阵与安装示例
- [ ] 本轮摘要发给用户：发了什么、跳过了什么、为什么

---

## 模式二：手动发布 + AI 验证

人照旧手动发（或用 `./deploy/release.sh bundle 17.x.y` 只生成签名包手动上传 Portal），
发完让 AI 跑：

```bash
./deploy/release.sh verify 17.x.y               # 三层验证，只验不发
./deploy/release.sh verify 17.x.y --target central
```

---

## 验证（三层，缺一不可）

| 层 | 手段 | 证明什么 |
|---|---|---|
| 回执 | deploy/上传 API 的返回码与状态 | 仓库收下了 |
| 拉取 | 从**远端**强制拉回 pom **+ jar**（`verify` 子命令；jar 才是用户真正要的，只验 POM 抓不到"POM-only"事故） | 用户真的能解析到（Central 同步延迟约 10~30 分钟，脚本会重试） |
| 冒烟 | 用 buka-template 生成钉住新版本的示例工程（**Boot 3.2 与 4.0 两个变体都跑**），`mvn package` 启动打接口 | 端到端能用 |

发 Central 后 repo1 短暂 404 是正常的（同步中）；Portal 状态 PUBLISHED 但超过 30 分钟仍拉不到 → 记实坑并排查。

**冒烟可机械执行的配方（2026-10-29 实测）：**

```bash
# 1. 本地起生成服务（buka-template 仓库内）
#    端口别用 8000/8001——前端 dev server 常驻占着，会打到别人身上
uv venv /tmp/buka-venv && uv pip install --python /tmp/buka-venv/bin/python -r requirements.txt
/tmp/buka-venv/bin/python -m uvicorn app.main:app --port 8123   # 冷端口
#    核对 /health 返回 {"status":"ok"}（带 timestamp 的是别的服务，说明端口被占）

# 2. 签名调用生成工程：POST /api/v1/templates/generate
#    HMAC 鉴权三头 + 签名算法（X-API-Key / X-Timestamp 毫秒 / X-Signature）见
#    ../../buka-template/RELEASE_SKILL.md「验证」节的签名配方
#    body: {"template_type":"soda-simple","project_name":"smoke32",
#           "context":{"package_name":"com.buka.demo","author":"smoke","spring_boot_version":"3.2"}}
#    spring_boot_version 取 "3.2" 和 "4.0" 各生成一个

# 3. 解压后 mvn package -DskipTests
#    （模板自带 SLSTest 打阿里云 SLS 占位项目 your-project、CustomRedisTemplateTest
#      切片缺 RedisConnectionFactory，离线必挂，与 soda 无关，待模板侧修）

# 4. java -jar target/*.jar 起应用（dev profile=H2 内存库，Redis 可选）：
#    curl :8080/check            → ok
#    curl -X POST :8080/api/creatProducer -d '{"name":"smoke-test"}'   → 200，uid 在 data.uid
#    curl -X POST :8080/api/getProducer   -d '{"uid":"<上面的uid>"}'   → 200 同一聚合
```

两个变体都走完 1~4 才算冒烟过；打不通的步骤记实坑。

---

## 裂线与移植（当前无任务；留存备用）

**原则：主线永不为兼容妥协；真断代才裂线，裂线后老线 best-effort、移植不了的特性明确跳过。**

历史线（2.x/2.1.x-boot32/4.0.0）一律 EOL，不再接受发布。若未来 Boot 5 等逼出不可兼容变更：

1. 从 main 切出新线（命名按矩阵：新的 Java 基线主版本），老线冻结
2. 按下表机械转换，修编译错误
3. 跑 `mvn clean verify` + 冒烟（双 Boot 变体）
4. **过不了验证门就不发那条线**，绝不硬发
5. 摘要里记录：移植了什么、跳过了什么

### 裂线差异映射表（对照用；踩到新差异就补一行）

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

**GPG 签名自动化前提（macOS）**：密钥带口令且无 TTY 时 `gpg -ab` 会报 `Inappropriate ioctl for device`。解法：`brew install pinentry-mac` 并在 `~/.gnupg/gpg-agent.conf` 写 `pinentry-program /opt/homebrew/bin/pinentry-mac`，签名时弹一次图形框输口令，同一次发布内自动复用。

---

## 目录速查

```
soda-core/
├── deploy/
│   ├── RELEASE_SKILL.md          # 本手册（仅 soda-core 发布；生成器发版见 ../../buka-template/RELEASE_SKILL.md）
│   ├── release.sh                # 发布脚本（入库）
│   ├── release.local.sh          # 密钥（gitignore，自行从 example 复制）
│   └── release.local.sh.example
├── soda-core-base/               # 模块 1（先于 event-redis 构建，reactor 自动排序）
├── soda-event-redis/             # 模块 2（依赖 base 的 ${project.version}）
├── prepare-maven-central-zip.sh  # 签名 bundle 打包（release.sh 会调用）
└── pom.xml
```

根目录的 `*-repo-bundle.zip`、`.zip-workspace/`、`log.txt`、`maven-migration.log` 都是构建垃圾（已被 gitignore），可随时删。
根目录的 `pom-2.3.xml`、`pom-2.7.xml`、`pom-3.2.xml`、`pom-profile.xml`、`package-*.sh` 是**历史构建残留（仍被 git 跟踪）**——多 POM 工作流已废弃，待重写 README「编译和构建」章节时一并清理。

---

## 实坑记录

> 每次发布踩坑，补一条：`YYYY-MM-DD 实坑：现象 → 根因 → 对策（脚本/清单已改哪里）`

- 2026-10-28 实坑：Maven Central 上 `2.1.1-boot32` **只有 POM 没有 JAR**（当年打包只传了 POM）→ 公开用户解析必挂 → 对策：弃用该版本（旧线用户用 `2.1.0-boot32`，jar 完好）；Central 版本不可删，只能靠新版本顶替。
- 2026-10-28 实坑：版本方案在"Boot 基线制"与"Java 基线制"间摇摆过一轮（pom 曾是 17.0，发布工具按 4.x 写，互相拧巴）→ 根因：Jackson 2→3 解耦后一套 jar 可双线，版本轴选错 → 对策：定稿 **Java 基线制**（主版本=Java 基线），版本矩阵、release.sh 守卫、模板 pin 已全部改齐。
- 2026-10-29 实坑：阿里云已有一份 17.0.0 但**内容过期**（摇摆期试验部署：缺 EventSerializer、还带 SmartCircularReferenceSerializerModifier 和 Jackson 依赖），且仓库默认禁止覆盖，`mvn deploy` 返回 **409**，set -e 直接把流程掐死在阿里云步（连 Central 都没发）→ 根因：冲突检查只看了"存不存在"没比对内容；覆盖策略没写进手册 → 对策：清单新增**冲突处理规则表**（Central 必换版本 / 阿里云先比对内容再决定删旧重发或跳过）；控制台开「允许重复部署」后重发正确内容。附：HTTP DELETE 删旧产物全部返回 **405**（packages 仓库不支持该动词，部署凭据也不行），清旧只能控制台操作。
- 2026-10-29 实坑（续）：409（禁覆盖）+ 405（禁 DELETE）把"清旧发新 17.0.0"彻底封死 → 决策：**改发 17.0.1**，阿里云 17.0.0 标废（内容过期、内部勿用），Central 首发即 17.0.1 → 教训：**版本号落进不可覆盖的仓库就不可回收**——别拿正式版本号做试验部署，版本号定稿、部署通道验证过再走发布。
- 2026-10-29 实坑：无 TTY 环境 `gpg -ab` 报 `Inappropriate ioctl for device` → 根因：密钥带口令、pinentry 只装了终端版 → 对策：装 pinentry-mac 写入 gpg-agent.conf（已补进「密钥与 git 忽略约定」）。
- 2026-10-29 实坑：**POM-only 事故的防线缺口还在**——`verify` 只拉 POM（2.1.1-boot32 那种"POM 有 JAR 无"照样验过）、`prepare-maven-central-zip.sh` 缺 JAR 只警告继续（还能再打出 POM-only 包）、bundle 合并用 `*-repo-bundle.zip` 通配（旧版本残留 zip 会混进上传）→ 对策：release.sh 8b87522 已改——verify 同时拉 jar、缺 jar/sources/javadoc 直接失败、合并只取当前版本 zip、tag 幂等（分次 --target 不会撞 tag）。
- 2026-10-29 实坑：`./deploy/release.sh ... | tee log` 把失败盖成成功（管道退出码取 tee 的），阿里云 409 那次第一眼没看出来 → 对策：执行注意已写进手册头部——要看退出码，别接管道。
- 2026-10-29 冒烟实录：生成工程自带的 SLSTest（打阿里云 SLS 占位项目 your-project）和 CustomRedisTemplateTest（切片测试缺 RedisConnectionFactory）离线必挂 → 与 soda 无关（RedisTest/JacksonConfigTest 等 soda 路径用例全过）；对策：冒烟 `mvn package -DskipTests` 后启动打接口（配方见上）；这两个用例是模板自带缺陷，条件化待办记在 `../../buka-template/RELEASE_SKILL.md` 实坑记录。
