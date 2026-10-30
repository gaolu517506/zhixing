package com.zhixing.config;

import com.zhixing.config.RouteProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 认证/首启向导装配：AuthProperties（线 A）与 DemoProperties（本线 F8）挂载点
 * （framework/config 包约定：新增 Properties 必须挂到对应装配类）。
 */
@Configuration
@EnableConfigurationProperties({AuthProperties.class, DemoProperties.class, RouteProperties.class})
public class AuthWebConfig {
}
