package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储配置（v2.0 扩展：type 切换 local/minio 双实现，D17——FileStorage 接口零改动）。
 * localRoot 允许相对路径（基于进程工作目录解析），默认 data/files。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.storage")
public class StorageProperties {

    /** 存储实现：local（默认，单机轻量）/ minio（企业存储体系） */
    private String type = "local";

    /** 存储根目录，落盘结构 {root}/{docId}/{原始文件名} */
    private String localRoot = "data/files";

    private final Minio minio = new Minio();

    /** MinIO 连接段（type=minio 时必填 endpoint/ak/sk/bucket） */
    @Data
    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket = "zhixing";
    }
}
