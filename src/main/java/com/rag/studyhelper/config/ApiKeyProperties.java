package com.rag.studyhelper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用层 API Key 认证（首期）：通过请求头校验，默认关闭以保持 Mock 零密钥演示。
 */
@Component
@ConfigurationProperties(prefix = "app.api-key")
public class ApiKeyProperties {

    /** 是否启用 API Key 校验 */
    private boolean enabled = false;

    /** 请求头名称 */
    private String header = "X-API-Key";

    /** 期望的 Key 值（仅环境变量注入，勿提交仓库） */
    private String value = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isConfigured() {
        return enabled && value != null && !value.trim().isEmpty();
    }
}
