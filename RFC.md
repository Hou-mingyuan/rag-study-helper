# RFC 状态：已被 ADR-0001 取代

旧 RFC 曾提议在保留 Java 8 主线的同时另建 Java 17 分支。该方案会产生两套半成品架构，已明确否决。

当前受支持决策只有：

- Java 17 + Spring Boot 3.5.16 单一主线；
- LangChain4j 1.18 release train；
- Flyway 管理可迁移 schema；
- MySQL 为活跃状态真源，向量通过确定性 ID、补偿和对账最终一致；
- 飞书删除由完整枚举、连续缺失、阈值和分布式锁共同保护；
- 本地单用户产品边界，知识空间全链路隔离；
- InMemory/Chroma/Milvus 使用同一项目契约。

完整上下文、依赖版本、迁移表和回退策略见 [docs/adr/0001-v2-runtime-and-reliability-architecture.md](docs/adr/0001-v2-runtime-and-reliability-architecture.md)。
