package com.zhixing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 认证线装配：AuthProperties 挂载点（framework/config 包约定：新增 Properties 必须挂到
 * 对应装配类）。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthWebConfig {
}
