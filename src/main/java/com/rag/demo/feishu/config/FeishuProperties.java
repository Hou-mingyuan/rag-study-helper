package com.rag.demo.feishu.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "app.feishu")
public class FeishuProperties {

    private String appId;
    private String appSecret;
    private String wikiId;
    private String cron = "0 0 */12 * * ?";
    private boolean syncEnabled = false;
}
