# RFC：rag-study-helper JDK 17 + Spring Boot 3 升级路径

> **状态**：草案（Phase-1 规划） · **实施**：Phase-2 pending  
> **日期**：2026-07-06  
> **动机**：LangChain4j ≥0.36 要求 JDK 17；Chroma 0.6.x+、Milvus 客户端新版本、Spring Boot 2.6 EOL 风险

---

## 1. 背景与约束

| 现状 | 升级后目标 |
| --- | --- |
| JDK 8 | **JDK 17**（LTS） |
| Spring Boot 2.6.13 | **Spring Boot 3.2.x**（与 JDK 17 对齐） |
| LangChain4j 0.35.0 | **0.36.x ~ 1.0.x**（取升级时最新稳定版） |
| Chroma 服务端 0.4.24 | **0.6.x+**（API 有 breaking change，需联调） |
| Milvus 2.3.x | **2.4.x / 2.5.x**（按 langchain4j-milvus 兼容矩阵） |
| `javax.*` | **`jakarta.*`**（Servlet 6） |

README 已有 JDK 8 锁定说明；本 RFC 定义**可评审、可分批落地**的迁移方案，**不在 Phase-1 改生产代码**。

---

## 2. 收益

1. 跟进 LangChain4j 新特性（Embedding Store API、RAG 编排改进）。
2. 解除 Chroma 0.4.24 锁定，简化 Hub 多 compose Profile 维护。
3. Spring Boot 3 原生可观测性（Micrometer OTel）、Security 6、虚拟线程（可选 3.2+）。
4. Docker 运行时可用 `eclipse-temurin:17-jre-alpine`，与作品集其他 Java 17 项目统一。

---

## 3. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| `javax` → `jakarta` 全量替换 | 分支 `feat/jdk17` 隔离；IDE 重构 + CI 编译门禁 |
| LangChain4j API breaking | 锁定 minor 版本；先跑 Mock 冒烟再接真实向量库 |
| Chroma compose 升级 | 保留 `docker-compose-chroma.yml` 旧版注释块作回滚 |
| Redisson / MyBatis-Plus Boot 3 starter | 使用官方 Boot 3 兼容版本（见 §5） |
| Hub Profile 端口矩阵 | 升级 PR 同步 `ai-portfolio` GAP-MATRIX |

**回滚**：`main` 保持 JDK 8 直至 Phase-2 全绿；feature 分支可随时废弃。

---

## 4. 分阶段计划

### Phase-1（本 RFC · 已完成）

- [x] 依赖对照表与 `pom` 属性草案（§5）
- [x] 文件级迁移清单（§6）
- [x] 验收标准定义（§7）

### Phase-2（实施 · pending）

1. 创建分支 `feat/jdk17-springboot3`
2. 更新 `pom.xml`、`Dockerfile`、`.github/workflows/ci.yml`
3. `javax` → `jakarta` 包名迁移（预计影响：无显式 servlet 代码，主要为传递依赖）
4. LangChain4j 配置类适配（`LangChain4jConfig`、`RagProviderResolver`）
5. Chroma / Milvus compose 文件版本 bump + 集成测试
6. README / USAGE / DEPLOYMENT 版本说明更新
7. `mvn test` + `docker compose up` + `scripts/smoke-mock-demo.mjs` 全绿

### Phase-3（可选）

- 虚拟线程（`spring.threads.virtual.enabled=true`）
- LangChain4j 1.x 新 RAG API 重构
- Trivy 镜像扫描 job

---

## 5. 依赖对齐草案（pom 属性 diff）

### 5.1 构建属性

| 属性 | 当前 (JDK 8) | 草案 (JDK 17) |
| --- | --- | --- |
| `java.version` | `1.8` | `17` |
| `spring-boot.version` | `2.6.13` | `3.2.12` |
| `langchain4j.version` | `0.35.0` | `0.36.2`（实施时复查最新 patch） |
| `lombok.version` | `1.18.32` | `1.18.34` |
| `maven-compiler-plugin` | `3.8.1` (source/target 1.8) | `3.13.0` (release 17) |
| `maven-surefire-plugin` | `2.22.2` | `3.2.5` |

### 5.2 直接依赖变更

| 依赖 | 当前 | 草案 | 说明 |
| --- | --- | --- | --- |
| `spring-boot-starter-*` | 2.6.13 BOM | 3.2.12 BOM | 随 Boot 升级 |
| `dev.langchain4j:*` | 0.35.0 | 0.36.2+ | 全模块同版本 |
| `com.baomidou:mybatis-plus-boot-starter` | 3.5.2 | **3.5.9** | 选 `spring-boot3-starter` 变体若可用 |
| `mysql:mysql-connector-java` | 8.0.33 | **`com.mysql:mysql-connector-j:8.3.0`** | artifact 迁移 |
| `org.redisson:redisson` | 3.24.3 | **3.34.0** | Boot 3 / JDK 17 兼容 |
| `org.apache.poi:poi-ooxml` | 5.1.0 | **5.2.5** | 安全补丁 |
| `org.jsoup:jsoup` | 1.15.4 | **1.18.1** | 安全补丁 |
| `commons-io:commons-io` | 2.11.0 | **2.16.1** | 安全补丁 |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | 4.12.0 | 保持；Boot 3 不再冲突 |
| `com.squareup.okhttp3:okhttp-sse` | 4.12.0 | 4.12.0 | 保持 |

### 5.3 基础设施镜像（compose / Dockerfile）

| 组件 | 当前 | 草案 |
| --- | --- | --- |
| App Dockerfile build | `maven:3.9-eclipse-temurin-8` | `maven:3.9-eclipse-temurin-17` |
| App Dockerfile runtime | `eclipse-temurin:8-jre-alpine` | `eclipse-temurin:17-jre-alpine` |
| Chroma | `chromadb/chroma:0.4.24` | `chromadb/chroma:0.6.3`（实施时 pin） |
| Milvus | `2.3.x` compose | `2.4.15` 或矩阵推荐版 |
| CI JDK | `8` | `17` |

### 5.4 pom.xml 属性片段（草案，未应用）

```xml
<properties>
    <java.version>17</java.version>
    <spring-boot.version>3.2.12</spring-boot.version>
    <langchain4j.version>0.36.2</langchain4j.version>
    <lombok.version>1.18.34</lombok.version>
</properties>
```

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.13.0</version>
    <configuration>
        <release>17</release>
        <encoding>UTF-8</encoding>
    </configuration>
</plugin>
```

---

## 6. 代码与配置迁移清单

| 区域 | 动作 |
| --- | --- |
| `src/main/java/**` | 检查 `javax.annotation` → `jakarta.annotation`（`@PostConstruct` 等） |
| `application.yml` | Boot 3 配置键变更（`spring.redis` 等多数兼容） |
| `LangChain4jConfig.java` | 对照 0.36 文档更新 Bean 工厂方法 |
| `docker-compose*.yml` | Chroma/Milvus 镜像 tag |
| `Dockerfile` / `.dockerignore` | Temurin 17 多阶段 |
| `.github/workflows/ci.yml` | `setup-java` → 17 |
| `README.md` | 移除 JDK 8 锁定警告，更新技术栈表 |
| `ai-portfolio` 矩阵 | JDK 17 / Chroma 新版本 |

---

## 7. 验收标准

| # | 检查项 | 命令 / 证据 |
| --- | --- | --- |
| 1 | 本 RFC 合并 | `RFC.md` 存在且评审通过 |
| 2 | Phase-2 编译 | `mvn -q -B test` JDK 17 绿 |
| 3 | Docker 构建 | 镜像体积记录在 README（参考当前 ~290MB → 17 JRE 预估 ~300MB） |
| 4 | Mock 冒烟 | `node scripts/smoke-mock-demo.mjs` 通过 |
| 5 | Chroma compose | `docker compose -f docker-compose-chroma.yml up` 入库 + 问答 |
| 6 | CI | GHA workflow JDK 17 绿 |

---

## 8. 决策记录

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 目标 JDK | 17 非 21 | 与作品集 LumiereBlogStudio 一致；LangChain4j 最低要求 |
| 目标 Boot | 3.2.x 非 3.4 | 保守 LTS 对齐，减少迁移面 |
| LangChain4j 跳跃 | 0.35 → 0.36 先，非直接 1.x | 降低 API 震荡；1.x 放 Phase-3 |
| 实施策略 | 单分支 big-bang | 模块耦合高（配置 + 向量库 + UI），分批收益低 |

---

## 9. 参考

- [README.md § JDK 8 兼容说明](./README.md)
- [docs/DIMENSION-AUDIT.md §10 P3](./docs/DIMENSION-AUDIT.md)
- [LangChain4j release notes](https://github.com/langchain4j/langchain4j/releases)
- [Spring Boot 3.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide)
