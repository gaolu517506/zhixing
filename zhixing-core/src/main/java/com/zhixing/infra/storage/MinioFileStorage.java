package com.zhixing.infra.storage;

import cn.hutool.core.io.file.FileNameUtil;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.config.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.MinioException;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MinIO/S3 对象存储实现（v2.0 F4，02-技术方案 §B4，D17 双实现）。
 *
 * <p>对象键 {docId}/{清洗后文件名}，与本地盘目录结构同构（T6-0 冻结接口只有 docId 维度；
 * 任务书 §1①的 {kbId} 前缀需要动接口，按红线「接口零改动」上报 lead 裁决）。
 * 删除语义对齐 LocalFileStorage：delete 静默容忍不存在（S3 删除天然幂等）；
 * deleteDocDir 按 docId 前缀列举后逐键删除（同键幂等，逐键而非批量 removeObjects——
 * 语义等价且对齐本地实现，批量 API 留作大目录优化）。
 *
 * <p>构造期只做配置 FAIL-FAST（缺 endpoint/ak/sk 启动即炸）；bucket 延迟确保（首次 store 时
 * bucketExists → 不存在则 makeBucket），网络不可达不影响应用启动。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zhixing.storage.type", havingValue = "minio")
public class MinioFileStorage implements FileStorage {

    private final MinioClient client;

    private final String bucket;

    private volatile boolean bucketEnsured;

    @Autowired
    public MinioFileStorage(StorageProperties props) {
        StorageProperties.Minio m = props.getMinio();
        if (isBlank(m.getEndpoint()) || isBlank(m.getAccessKey()) || isBlank(m.getSecretKey())) {
            throw new IllegalStateException("zhixing.storage.type=minio 需要完整 minio 配置段"
                    + "（endpoint/access-key/secret-key，见 application.yaml zhixing.storage.minio）");
        }
        this.client = MinioClient.builder()
                .endpoint(m.getEndpoint())
                .credentials(m.getAccessKey(), m.getSecretKey())
                .build();
        this.bucket = m.getBucket();
        log.info("MinIO 文件存储装配：endpoint={} bucket={}", m.getEndpoint(), bucket);
    }

    /** 测试注入口：桩 MinioClient + 指定 bucket（Mockito 桩用）。 */
    MinioFileStorage(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public String store(long docId, String fileName, byte[] content) {
        // 只取文件名部分，杜绝路径穿越（原始文件名来自客户端，不可信）——与本地实现同一纪律
        String safeName = FileNameUtil.getName(fileName);
        String key = docId + "/" + safeName;
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                    .build());
            return key;
        } catch (MinioException e) {
            log.error("MinIO 存储失败 key={}", key, e);
            throw new BusinessException(ErrorCode.FILE_STORE_FAILED, "文件保存失败：" + safeName);
        }
    }

    @Override
    public byte[] read(String path) {
        try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(path).build())) {
            return response.readAllBytes();
        } catch (MinioException | IOException e) {
            log.error("MinIO 读取失败 path={}", path, e);
            throw new BusinessException(ErrorCode.FILE_READ_FAILED, "文件读取失败：" + path);
        }
    }

    @Override
    public void delete(String path) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(path).build());
        } catch (MinioException e) {
            throw new IllegalStateException("文件删除失败：" + path, e);
        }
    }

    @Override
    public void deleteDocDir(long docId) {
        String prefix = docId + "/";
        List<String> keys = new ArrayList<>();
        try {
            for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build())) {
                keys.add(result.get().objectName());
            }
        } catch (MinioException e) {
            throw new IllegalStateException("文档目录列举失败：" + prefix, e);
        }
        if (keys.isEmpty()) {
            return; // 目录不存在静默返回（对齐本地实现）
        }
        for (String key : keys) {
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            } catch (MinioException e) {
                throw new IllegalStateException("文档目录删除失败：" + key, e);
            }
        }
    }

    /** bucket 延迟确保：幂等，双检锁避免并发首传时重复探测。 */
    private void ensureBucket() throws MinioException {
        if (bucketEnsured) {
            return;
        }
        synchronized (this) {
            if (bucketEnsured) {
                return;
            }
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 不存在，已创建：{}", bucket);
            }
            bucketEnsured = true;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
