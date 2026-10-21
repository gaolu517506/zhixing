package com.zhixing.infra.storage;

import cn.hutool.core.io.file.FileNameUtil;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地磁盘存储实现。根目录相对路径基于进程工作目录解析（默认 data/files）。
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(StorageProperties props) {
        this.root = Path.of(props.getLocalRoot()).toAbsolutePath().normalize();
        log.info("本地文件存储根目录：{}", root);
    }

    @Override
    public String store(long docId, String fileName, byte[] content) {
        // 只取文件名部分，杜绝路径穿越（原始文件名来自客户端，不可信）
        String safeName = FileNameUtil.getName(fileName);
        Path dir = root.resolve(Long.toString(docId));
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(safeName);
            Files.write(target, content);
            return docId + "/" + safeName;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_STORE_FAILED, "文件保存失败：" + safeName);
        }
    }

    @Override
    public byte[] read(String path) {
        try {
            return Files.readAllBytes(root.resolve(path));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_READ_FAILED, "文件读取失败：" + path);
        }
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(root.resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException("文件删除失败：" + path, e);
        }
    }

    @Override
    public void deleteDocDir(long docId) {
        Path dir = root.resolve(Long.toString(docId));
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            // 逆序删除：先文件后目录
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("文档目录删除失败：" + dir, e);
        }
    }
}
