package com.rag.studyhelper.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置。
 * <p>
 * 复用 spring.redis.* 连接参数，与 Spring Data Redis 共享同一个 Redis 实例。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:localhost}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        // 单机模式
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionPoolSize(4)
                .setConnectionMinimumIdleSize(1);
        if (StringUtils.hasText(password)) {
            config.useSingleServer().setPassword(password);
        }

        // 集群模式
//        config.useClusterServers()
//                .addNodeAddress("redis://<your-redis-node1-ip>:<port1>",
//                        "redis://<your-redis-node2-ip>:<port2>",
//                        "redis://<your-redis-node3-ip>:<port3>")
//                // 设置密码（如果集群有密码）
//                .setPassword(password)
//                // 主节点连接池大小
//                .setMasterConnectionPoolSize(4)
//                // 主节点最小空闲连接数
//                .setMasterConnectionMinimumIdleSize(1)
//                // 从节点连接池配置
//                .setSlaveConnectionPoolSize(4)
//                // 从节点最小空闲连接数
//                .setSlaveConnectionMinimumIdleSize(1)
//                // 读负载均衡
//                .setReadMode(ReadMode.SLAVE)
//                // 集群状态扫描间隔（毫秒），用于自动发现拓扑变更
//                .setScanInterval(2000);
        return Redisson.create(config);
    }
}
