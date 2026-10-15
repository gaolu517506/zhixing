package com.zhixing.core.ingest;

import cn.hutool.core.io.file.FileNameUtil;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/**
 * Tika 全文解析器：一个依赖覆盖 pdf/docx/pptx/html/txt/md。
 * 判能以扩展名为准（mime 留参数位）；解析全程只产出纯文本，不保页序（ParsedDocument.pages 为空）。
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName, String mimeType) {
        String ext = FileNameUtil.extName(fileName == null ? "" : fileName).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    @Override
    public ParsedDocument parse(File file) {
        // writeLimit -1：不设 BodyContentHandler 默认 100k 写入上限，50MB 上传全量解析
        try (InputStream in = java.nio.file.Files.newInputStream(file.toPath())) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            new AutoDetectParser().parse(in, handler, new Metadata(), new ParseContext());
            return ParsedDocument.of(handler.toString().trim());
        } catch (Exception e) {
            // 对外只说"解析失败"，原始异常随堆栈由 Pipeline 记日志
            log.error("Tika 解析失败：{}", file.getName(), e);
            throw new BusinessException(ErrorCode.FILE_READ_FAILED, "文档解析失败：" + file.getName());
        }
    }
}
