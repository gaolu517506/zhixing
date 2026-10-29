package com.zhixing.service;

import org.springframework.lang.NonNull;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 内存字节 MultipartFile 适配（演示库种子上传用）：KbService.upload 的契约入口是
 * Spring MultipartFile，种子文档来自 classpath 字节，无需走临时文件桥接。
 * transferTo 按契约实现（写临时文件），种子链路不会走到它。
 */
public final class BytesMultipartFile implements MultipartFile {

    private final String name;

    private final String originalFilename;

    private final String contentType;

    private final byte[] bytes;

    public BytesMultipartFile(String originalFilename, byte[] bytes) {
        this.name = "file";
        this.originalFilename = originalFilename;
        this.contentType = contentTypeOf(originalFilename);
        this.bytes = bytes;
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    @NonNull
    public byte[] getBytes() {
        return bytes;
    }

    @Override
    @NonNull
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(@NonNull File dest) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(bytes);
        }
    }

    private static String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".html")) {
            return "text/html";
        }
        return "application/octet-stream";
    }
}
