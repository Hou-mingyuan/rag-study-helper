package com.rag.studyhelper.feishu.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 飞书配置类
 * 逻辑是先创建一个飞书应用 获得 appId + appSecret
 * 飞书应用设置权限（读取 wiki 文档等 + 机器人）后上线应用
 * 创建一个飞书的群聊把机器人加入群聊
 * 创建一个 wiki 空间，获取 spaceId 并配置管理员权限给刚才创建的群聊
 * 通过 appId + appSecret 获取 飞书应用（机器人）权限（tenant_access_token）
 * tenant_access_token（机器人）有 wiki 空间的权限，通过 spaceId 获取空间下所有文档
 */
@Data
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "app.feishu")
public class FeishuProperties {

    /**
     * 飞书 AppId
     */
    private String appId;
    /**
     * 飞书 AppSecret
     */
    private String appSecret;
    /**
     * 知识库空间 ID
     */
    private String spaceId;
    /**
     * 定时任务执行间隔，默认 12 小时
     * 后面可以自己改造一下，使用 xxl-job，这个项目只供学习 RAG ，就没加重架构了
     */
    private String cron = "0 0 */12 * * ?";
    /**
     * 是否开启飞书同步功能 （默认关闭）
     */
    private boolean syncEnabled = false;
}
