package com.zhixing.infra.storage;

import com.zhixing.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** 契约套件本地实现实例（真盘 @TempDir，随用例即建即弃）。 */
class LocalFileStorageContractTest extends FileStorageContract {

    @TempDir
    Path tempDir;

    @Override
    protected FileStorage storage() {
        StorageProperties props = new StorageProperties();
        props.setLocalRoot(tempDir.toString());
        return new LocalFileStorage(props);
    }

    @Test
    void 装配注解_默认type为local生效() throws Exception {
        // 条件装配回归锁：matchIfMissing=true——未配置 type 时本实现必须顶位（D17 默认单机）
        var annotation = LocalFileStorage.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        org.assertj.core.api.Assertions.assertThat(annotation).isNotNull();
        org.assertj.core.api.Assertions.assertThat(annotation.matchIfMissing()).isTrue();
        org.assertj.core.api.Assertions.assertThat(annotation.havingValue()).isEqualTo("local");
    }
}
