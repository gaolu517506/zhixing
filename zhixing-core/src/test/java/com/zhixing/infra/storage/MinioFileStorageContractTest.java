package com.zhixing.infra.storage;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.MinioException;
import io.minio.messages.Item;
import okhttp3.Headers;
import com.zhixing.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 契约套件 MinIO 实现实例：接口级 fake（Mockito 桩 MinioClient + 内存 map），
 * 断言两实现行为一致（02-技术方案 §B4 契约复用；testcontainers 留真机冒烟替代）。
 * 桩行为按 S3 语义：put 覆盖、get 缺失抛 MinioException、remove 幂等、list 按前缀。
 */
class MinioFileStorageContractTest extends FileStorageContract {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    private final MinioClient client = mock(MinioClient.class);

    @BeforeEach
    void stubClient() throws MinioException {
        objects.clear();
        when(client.putObject(any(PutObjectArgs.class))).thenAnswer(inv -> {
            PutObjectArgs args = inv.getArgument(0);
            // stream 模式构建的 args 不携带 data()，按流读回
            objects.put(args.object(), args.stream().readAllBytes());
            return null;
        });
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(inv -> {
            GetObjectArgs args = inv.getArgument(0);
            byte[] data = objects.get(args.object());
            if (data == null) {
                throw new MinioException("The specified key does not exist.（fake）");
            }
            return new GetObjectResponse(Headers.of(), "zhixing", null, args.object(),
                    new ByteArrayInputStream(data));
        });
        doAnswer(inv -> {
            RemoveObjectArgs args = inv.getArgument(0);
            objects.remove(args.object());
            return null;
        }).when(client).removeObject(any(RemoveObjectArgs.class));
        when(client.listObjects(any(ListObjectsArgs.class))).thenAnswer(inv -> {
            ListObjectsArgs args = inv.getArgument(0);
            List<Result<Item>> results = new ArrayList<>();
            for (String key : objects.keySet().stream().filter(k -> k.startsWith(args.prefix())).sorted().toList()) {
                Item item = mock(Item.class);
                when(item.objectName()).thenReturn(key);
                results.add(new Result<>(item));
            }
            return results;
        });
        when(client.bucketExists(any())).thenReturn(true);
    }

    @Override
    protected FileStorage storage() {
        return new MinioFileStorage(client, "zhixing");
    }

    @Test
    void 装配注解_type为minio时生效且不缺省() {
        var annotation = MinioFileStorage.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.havingValue()).isEqualTo("minio");
        assertThat(annotation.matchIfMissing()).isFalse();
    }

    @Test
    void 配置缺失时构造FAIL_FAST() {
        StorageProperties props = new StorageProperties();
        props.setType("minio");
        // minio 段全空：缺 endpoint/ak/sk 启动即炸（配置错误不留到调用期）
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MinioFileStorage(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minio");
    }
}
