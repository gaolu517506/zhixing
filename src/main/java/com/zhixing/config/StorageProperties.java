package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地文件存储配置。root 允许相对路径（基于进程工作目录解析），默认 data/files。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.storage")
public class StorageProperties {

    /** 存储根目录，落盘结构 {root}/{docId}/{原始文件名} */
    private String localRoot = "data/files";
}
