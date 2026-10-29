package com.zhixing.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开发期跨域：放行 Vite dev 源（M1 默认 5173 + E 线走查端口 5174），方法覆盖前端全部
 * 动词（admin 后台 PUT/PATCH——闸门 2b 真机走查发现的合并级缺陷，DL-28）。
 * 生产前后端同源部署不经此配置，不受影响。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:5174")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
