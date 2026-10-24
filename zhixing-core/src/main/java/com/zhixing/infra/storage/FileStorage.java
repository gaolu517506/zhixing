package com.zhixing.infra.storage;

/**
 * 文件存储抽象。阶段 1 只有本地磁盘实现；接口留扩展位（如日后换 MinIO），调用方零改动。
 * path 一律是相对存储根目录的相对路径，由实现自行解析。
 */
public interface FileStorage {

    /** 落盘到 {root}/{docId}/{fileName}，返回相对路径（"{docId}/{fileName}"）。 */
    String store(long docId, String fileName, byte[] content);

    /** 按相对路径读全量字节；文件不存在或 IO 失败抛 BusinessException(FILE_READ_FAILED)。 */
    byte[] read(String path);

    /** 删除静默容忍不存在；IO 失败抛异常，由调用方决定是否回滚（磁盘不是事务资源，删除失败只告警）。 */
    void delete(String path);

    /** 删除 docId 的整个磁盘目录（删库级联用）；目录不存在时静默返回；IO 失败抛异常由调用方兜底。 */
    void deleteDocDir(long docId);
}
