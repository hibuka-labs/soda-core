# Spring Boot 4 Upgrade Plan

## 目标
将 soda-core 从 Spring Boot 3.2.12 升级到 Spring Boot 4.1.0，同时保持向后兼容。
用两个 sample 工程验证兼容性后发布 Maven Central。

## 已完成

### soda-core 主工程 ✅
- 版本: `2.1.2-boot32` → `4.0.0`
- Spring Boot: 3.2.12 → 4.1.0
- Java: 17 → 25
- Lombok: 1.18.36 → 1.18.46
- Jackson: 2.x → 3.x (com.fasterxml.jackson → tools.jackson.core)
- 旧版 tag: `v2.1.2-boot32` 保留在 main 分支历史中
- **编译通过**: `mvn clean compile -DskipTests` ✅

### soda-core 源码变更
- `BeanSerializerModifier` → `ValueSerializerModifier`
- `JsonSerializer` → `ValueSerializer`
- `ObjectMapper.configure()` → `JsonMapper.builder().enable()/disable()`
- `ObjectMapper.DefaultTyping` → `DefaultTyping` (顶层枚举)
- `addMixIn()` 移到 builder 上
- `activateDefaultTyping()` 移到 builder，用 `BasicPolymorphicTypeValidator`
- 移除 `JavaTimeModule`（Jackson 3 内置）
- Starter: `spring-boot-starter-aop` → `spring-boot-starter-aspectj`
- `@AutoConfigureAfter(RedisAutoConfiguration.class)` → 字符串引用（SB4 模块化）

---

## 待完成

### 1. buka-simple-4-test 升级（关键验证项目）✅

**pom.xml 已更新:**
- Spring Boot 3.2.12 → 4.1.0
- Java 17 → 25
- soda: 2.1.0-boot32 → 4.0.0
- MyBatis-Plus: boot3-starter:3.5.8 → boot4-starter:3.5.16
- 新增: mybatis-plus-jsqlparser, spring-boot-starter-jackson
- 新增: tools.jackson.core:jackson-databind
- 移除: jackson-datatype-jsr310, Spring Cloud / Nacos 依赖（dev 已禁用）
- maven-compiler-plugin 3.13.0 + Lombok annotation processor

**源码变更:**
| 文件 | 变更 | 原因 |
|------|------|------|
| `JacksonConfig.java` | 删除 | no-op Bean，JsonMapperBuilderCustomizer 在 SB4 被移出核心模块 |
| `RedisConfig.java` | `GenericJackson2JsonRedisSerializer` → `GenericJacksonJsonRedisSerializer` + 注入 `ObjectMapper` | SB4 Spring Data Redis 类名和构造器变更 |
| `SampleController.java:67` | `PageResult.success()` 参数顺序: `(data, page, size, total)` → `(data, total, page, size)` | soda-core 4.0.0 API 变更 |
| `CustomRedisTemplateTest.java` | 同 RedisConfig 的序列化器变更 | 一致 |
| `application-dev.yaml` | 删除 Nacos 配置 | Spring Cloud / Nacos 依赖已移除 |
| `MybatisPlusConfig.java` | 无变更 | 已通过 mybatis-plus-jsqlparser 依赖解决 ✅ |

**编译状态: `mvn clean test-compile` ✅ (0 errors)**
- 位置: `/Users/kangzengchen/Downloads/buka-simple-4-test`

**还需验证:**
- 应用启动 (`mvn spring-boot:run`)
- Redis 事件总线: Simple 模式和 Stream 模式
- 幂等性 (idempotency)
- SLS (阿里云日志服务) 兼容性
- Knife4j 4.3.0 兼容性

### 2. buka-cloud-4-test 升级
- 多模块项目，类似改动
- 验证 Spring Cloud + Nacos 兼容性 (需确认 Spring Cloud Alibaba 是否有 SB4 版本)

### 3. 发布
- 更新 `package-2.1.2.sh` 为新版本 4.0.0
- 运行发布脚本打包
- 上传 Maven Central (Sonatype OSSRH)

---

## 已知兼容性风险

| 组件 | 风险 | 状态 |
|------|------|------|
| knife4j 4.3.0 | 可能不兼容 SB4 | 待验证 |
| aliyun-log 0.6.75 | 可能不兼容 SB4 | 待验证 |
| Spring Cloud Alibaba | 2023.x 不兼容 SB4 | 需要 2025.x 版本 |
| MyBatis-Plus | 已升级到 boot4-starter:3.5.16 | ✅ |
| Redis 连接 | host 是 mysql.rds 域名 | 待确认 |

---

## 技术要点备忘

### Jackson 3 迁移速查
```
Jackson 2                    → Jackson 3
com.fasterxml.jackson.databind → tools.jackson.databind
ObjectMapper.configure(X, b)   → builder.enable(X) / builder.disable(X)
new ObjectMapper()             → JsonMapper.builder()...build()
BeanSerializerModifier         → ValueSerializerModifier
JsonSerializer                 → ValueSerializer
ObjectMapper.DefaultTyping     → DefaultTyping
JavaTimeModule                 → 内置，无需注册
jackson-datatype-jsr310        → 移除，已内置
addMixIn(target, mixin)        → builder.addMixIn(target, mixin)
```

### Spring Boot 4 Starter 重命名
```
SB3                          → SB4
spring-boot-starter-web       → spring-boot-starter-webmvc
spring-boot-starter-aop       → spring-boot-starter-aspectj
spring-boot-starter-json      → spring-boot-starter-jackson
```
