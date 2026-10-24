package com.zhixing.core.ingest;

import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.service.KbService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 入库线冒烟（真实调用智谱 embedding，花钱）：INGEST_SMOKE=true 且有 ZHIPU_API_KEY 时才跑。
 * 与 LlmSmokeTest 同一门控写法，默认跳过——执行须 lead 批准：
 * <pre>INGEST_SMOKE=true ZHIPU_API_KEY=sk-xxx mvn test -Dtest=IngestSmokeTest</pre>
 * 说明：极小 PDF 用 Helvetica 标准字体（无法嵌中文字体），文本用 ASCII——中文抽取
 * 由 Tika 全文解析天然覆盖，冒烟目的只在"链路到 COMPLETED、chunk_count > 0"。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "INGEST_SMOKE", matches = "true")
class IngestSmokeTest {

    @Autowired
    private KbService kbService;

    @Autowired
    private KbDocumentMapper docMapper;

    @BeforeAll
    static void requireKey() {
        assumeTrue(System.getenv("ZHIPU_API_KEY") != null && !System.getenv("ZHIPU_API_KEY").isBlank(),
                "缺少 ZHIPU_API_KEY，跳过入库冒烟");
    }

    /** 冒烟②：上传 PDF → 轮询 60s 内到 COMPLETED 且 chunk_count > 0（详设 §1 验收 1 的收缩版）。 */
    @Test
    void 冒烟_上传PDF到COMPLETED且分块大于0() throws Exception {
        KbKnowledgeBase kb = kbService.createKb("冒烟-入库线-" + System.currentTimeMillis(), null);
        byte[] pdf = MinimalPdf.onePage(
                "Xiaomi 15 Ultra battery is 6000mAh with 90W wired charging and IP68 rating. "
                        + "Redmi K80 battery is 6550mAh with 90W wired charging and no wireless charging. "
                        + "Xiaomi Pad 7 battery is 8850mAh with 45W charging. Warranty period is 1 year.");
        KbService.UploadOutcome outcome = kbService.upload(kb.getId(),
                new MockMultipartFile("file", "smoke.pdf", "application/pdf", pdf));
        assertThat(outcome.idempotent()).isFalse();

        long docId = outcome.document().getId();
        long deadline = System.currentTimeMillis() + 60_000;
        KbDocument doc = docMapper.selectById(docId);
        while (doc != null && !"COMPLETED".equals(doc.getStatus()) && !"FAILED".equals(doc.getStatus())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            doc = docMapper.selectById(docId);
        }

        assertThat(doc).isNotNull();
        assertThat(doc.getStatus()).isEqualTo("COMPLETED");
        assertThat(doc.getChunkCount()).isGreaterThan(0);
        System.out.println("[入库冒烟] docId=" + doc.getId() + " status=" + doc.getStatus()
                + " chunkCount=" + doc.getChunkCount());
    }

    /** 内存中构造一页极小合法 PDF（对象偏移精确计算），只含一段 ASCII 文本。 */
    static final class MinimalPdf {

        private MinimalPdf() {
        }

        static byte[] onePage(String asciiText) {
            // 内容流里的 ( ) \ 需转义；冒烟文本自控，防御性处理即可
            String safe = asciiText.replaceAll("[()\\\\]", " ");
            byte[] stream = ("BT /F1 12 Tf 72 720 Td (" + safe + ") Tj ET\n")
                    .getBytes(StandardCharsets.US_ASCII);

            String[] objects = {
                    "<< /Type /Catalog /Pages 2 0 R >>",
                    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                            + "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                    null,   // 4 号对象是内容流，单独拼
                    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
            };

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            int[] offsets = new int[objects.length + 1];
            for (int i = 1; i <= objects.length; i++) {
                offsets[i] = out.size();
                if (i == 4) {
                    out.writeBytes(("4 0 obj\n<< /Length " + stream.length + " >>\nstream\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.writeBytes(stream);
                    out.writeBytes("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
                } else {
                    out.writeBytes((i + " 0 obj\n" + objects[i - 1] + "\nendobj\n")
                            .getBytes(StandardCharsets.US_ASCII));
                }
            }
            int xrefOffset = out.size();
            StringBuilder xref = new StringBuilder("xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f \n");
            for (int i = 1; i <= objects.length; i++) {
                xref.append(String.format("%010d 00000 n \n", offsets[i]));
            }
            xref.append("trailer\n<< /Size ").append(objects.length + 1)
                    .append(" /Root 1 0 R >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");
            out.writeBytes(xref.toString().getBytes(StandardCharsets.US_ASCII));
            return out.toByteArray();
        }
    }
}
