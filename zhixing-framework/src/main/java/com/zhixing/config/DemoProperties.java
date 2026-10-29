package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.demo 配置段绑定（首启演示库种子，F8 后端半，02-技术方案 §B8）。
 * seed-enabled=true 时首启向导可在创建管理员的同时加载 classpath 内置演示文档。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.demo")
public class DemoProperties {

    /** 是否提供演示库种子能力（不影响 setup 本身）。 */
    private boolean seedEnabled = true;

    /** 演示库名称。 */
    private String kbName = "演示库";

    /** 演示文档 classpath 模式（仓库 resources/demo/，docs/ 不入库铁律）。 */
    private String docsPattern = "classpath:demo/*";
}
