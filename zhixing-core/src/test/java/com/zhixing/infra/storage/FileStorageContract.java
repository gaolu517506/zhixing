package com.zhixing.infra.storage;

import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FileStorage 契约测试套件（02-技术方案 §B4：契约套件对双实现复用）。
 * 抽象用例由 LocalFileStorageContractTest（真盘 @TempDir）与
 * MinioFileStorageContractTest（Mockito 桩 MinioClient）分别实例化——
 * 两实现必须在「返回路径形态 / 读缺失 / 删静默 / 前缀删」上行为一致（D17 可互换）。
 */
abstract class FileStorageContract {

    protected static final byte[] CONTENT = "小米 15 Ultra 电池容量 6000mAh [1]".getBytes(StandardCharsets.UTF_8);

    /** 被测实现：每个用例拿全新实例（MinIO 侧内部 map 随实例重建）。 */
    protected abstract FileStorage storage();

    @Test
    void store返回相对路径且read回读一致() {
        String path = storage().store(42L, "参数说明.md", CONTENT);

        assertThat(path).isEqualTo("42/参数说明.md");
        assertThat(storage().read(path)).isEqualTo(CONTENT);
    }

    @Test
    void store清洗路径穿越文件名() {
        String path = storage().store(42L, "../../etc/evil.txt", CONTENT);

        // 只留 basename，路径落回 docId 目录内
        assertThat(path).isEqualTo("42/evil.txt");
        assertThat(storage().read(path)).isEqualTo(CONTENT);
    }

    @Test
    void 同键重复store覆盖写() {
        String path = storage().store(42L, "a.md", "旧内容".getBytes(StandardCharsets.UTF_8));
        storage().store(42L, "a.md", CONTENT);

        assertThat(storage().read(path)).isEqualTo(CONTENT);
    }

    @Test
    void read不存在抛FILE_READ_FAILED() {
        assertThatThrownBy(() -> storage().read("999999/missing.md"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("missing.md");
        assertThatThrownBy(() -> storage().read("999999/missing.md"))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_READ_FAILED);
    }

    @Test
    void delete不存在静默() {
        storage().delete("999999/never-existed.md"); // 不抛即过
    }

    @Test
    void deleteDocDir删除前缀下全部对象() {
        storage().store(42L, "a.md", CONTENT);
        storage().store(42L, "b.md", CONTENT);
        storage().store(43L, "other.md", CONTENT);

        storage().deleteDocDir(42L);

        assertThatThrownBy(() -> storage().read("42/a.md")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage().read("42/b.md")).isInstanceOf(BusinessException.class);
        assertThat(storage().read("43/other.md")).isEqualTo(CONTENT); // 邻居不受影响
    }

    @Test
    void deleteDocDir不存在静默() {
        storage().deleteDocDir(888888L); // 不抛即过
    }
}
