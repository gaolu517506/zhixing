package com.zhixing.service;

import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.config.DemoProperties;
import com.zhixing.dao.entity.KbKnowledgeBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首启演示库种子单测（02 §B8 测试点）：seedAvailable 诚实呈现、播种链路（建库+逐篇上传）、
 * 种子过程身份注入与清除、单文档读取失败不阻断整库。
 */
class DemoSeedServiceTest {

    private KbService kbService;

    private DemoProperties props;

    private DemoSeedService service;

    private static final CurrentUser OWNER =
            new CurrentUser(7L, "root", "ADMIN", AuthChannel.WEB);

    @BeforeEach
    void setUp() {
        kbService = mock(KbService.class);
        props = new DemoProperties();
        service = new DemoSeedService(kbService, props);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 带文件名的内存资源（ByteArrayResource 的 description 不参与 getFilename，须覆写）。 */
    private static Resource namedResource(String fileName, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private DemoSeedService withResources(Resource... resources) {
        return new DemoSeedService(kbService, props) {
            @Override
            protected List<Resource> loadDemoResources() {
                return List.of(resources);
            }
        };
    }

    private void stubCreateKb(long kbId) {
        when(kbService.createKb(anyString(), anyString())).thenAnswer(inv -> {
            KbKnowledgeBase kb = new KbKnowledgeBase();
            kb.setId(kbId);
            kb.setName(inv.getArgument(0));
            return kb;
        });
    }

    @Test
    void seedAvailable_开关关闭为false() {
        props.setSeedEnabled(false);
        assertThat(service.seedAvailable()).isFalse();
    }

    @Test
    void seedAvailable_资源缺失为false_资源存在为true() {
        assertThat(withResources().seedAvailable()).isFalse();
        assertThat(withResources(namedResource("a.md", "x"))
                .seedAvailable()).isTrue();
    }

    @Test
    void seedLibrary_建库并逐篇上传_身份注入后清除() {
        DemoSeedService seeded = withResources(
                namedResource("01-参数.md", "doc1"),
                namedResource("02-保修.md", "doc2"));
        stubCreateKb(99L);

        long kbId = seeded.seedLibrary(OWNER);

        assertThat(kbId).isEqualTo(99L);
        verify(kbService).createKb("演示库", "首启演示库（内置示例文档）");
        verify(kbService, times(2)).upload(eq(99L), any(org.springframework.web.multipart.MultipartFile.class));
        // 种子过程临时身份已清除（不泄漏到后续请求）
        assertThat(AuthContext.currentOrNull()).isNull();
    }

    @Test
    void seedLibrary_单文档读取失败不阻断整库() {
        // 第二篇 content 读取失败（IOException 被吞），第一篇正常入库
        Resource broken = new ByteArrayResource("broken".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "02-坏档.md";
            }

            @Override
            public byte[] getContentAsByteArray() throws IOException {
                throw new IOException("classroom gone");
            }
        };
        DemoSeedService seeded = withResources(
                namedResource("01-参数.md", "doc1"),
                broken);
        stubCreateKb(88L);

        long kbId = seeded.seedLibrary(OWNER);

        assertThat(kbId).isEqualTo(88L);
        verify(kbService, times(1)).upload(eq(88L), any(org.springframework.web.multipart.MultipartFile.class));
        assertThat(AuthContext.currentOrNull()).isNull();
    }

    @Test
    void seedLibrary_种子不可用时拒绝() {
        props.setSeedEnabled(false);
        assertThatThrownBy(() -> service.seedLibrary(OWNER)).isInstanceOf(IllegalStateException.class);
        verify(kbService, never()).createKb(anyString(), anyString());
    }
}
