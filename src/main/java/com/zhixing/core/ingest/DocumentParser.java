package com.zhixing.core.ingest;

import java.io.File;
import java.util.Set;

/**
 * 文档解析器 SPI。阶段 1 只有 Tika 一种实现；接口留扩展位。
 */
public interface DocumentParser {

    /** 支持的扩展名（小写、不含点）。Service 层上传校验与解析器判能共用这一份清单。 */
    Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "docx", "pptx", "html", "txt", "md");

    boolean supports(String fileName, String mimeType);

    ParsedDocument parse(File file);
}
