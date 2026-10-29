package com.zhixing.service;

import com.zhixing.common.BusinessException;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.config.StorageProperties;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.infra.llm.ErrorType;
import com.zhixing.infra.llm.ModelClientException;
import com.zhixing.infra.llm.ResilientChatDecorator;
import com.zhixing.infra.storage.LocalFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 健康聚合单测（02 §B7 测试点）：非 ADMIN 403、DB ping 现态、本地存储探测、
 * 计数聚合；熔断现态经真实 ResilientChatDecorator 反射读取（OPEN 现态验证）。
 */
class AdminHealthServiceTest {

    private JdbcTemplate jdbcTemplate;

    private StorageProperties storageProps;

    private LocalFileStorage fileStorage;

    private AdminHealthService service;

    private com.zhixing.infra.llm.ChatClient chatClient;

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", "ADMIN", AuthChannel.WEB);

    private static final CurrentUser USER = new CurrentUser(2L, "u", "USER", AuthChannel.WEB);

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        storageProps = new StorageProperties();
        storageProps.setLocalRoot(tempDir.toString());
        fileStorage = mock(LocalFileStorage.class);
        chatClient = fakeResilientClient();
        service = new AdminHealthService(jdbcTemplate, storageProps, fileStorage,
                chatClient, mock(KbDocumentMapper.class), mock(KbKnowledgeBaseMapper.class),
                mock(AuthUserMapper.class), mock(AgentRunMapper.class));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 真 ResilientChatDecorator：两个 fake 候选，候选 1 连续可降级失败 → 熔断 OPEN。 */
    private com.zhixing.infra.llm.ChatClient fakeResilientClient() {
        com.zhixing.infra.llm.ChatClient bad = new com.zhixing.infra.llm.ChatClient() {
            @Override
            public String provider() {
                return "fakebad";
            }

            @Override
            public com.zhixing.infra.llm.dto.ChatResponse call(com.zhixing.infra.llm.dto.ChatRequest request) {
                throw new ModelClientException(ErrorType.SERVER_ERROR, "boom");
            }

            @Override
            public void stream(com.zhixing.infra.llm.dto.ChatRequest request,
                               com.zhixing.infra.llm.StreamCallback callback) {
                callback.onError(new ModelClientException(ErrorType.SERVER_ERROR, "boom"));
            }
        };
        com.zhixing.infra.llm.ChatClient good = new com.zhixing.infra.llm.ChatClient() {
            @Override
            public String provider() {
                return "fakegood";
            }

            @Override
            public com.zhixing.infra.llm.dto.ChatResponse call(com.zhixing.infra.llm.dto.ChatRequest request) {
                return new com.zhixing.infra.llm.dto.ChatResponse("ok", "stop", null);
            }

            @Override
            public void stream(com.zhixing.infra.llm.dto.ChatRequest request,
                               com.zhixing.infra.llm.StreamCallback callback) {
                callback.onContent("ok");
                callback.onComplete("stop", null);
            }
        };
        // failure-threshold=1：一次可降级失败即 OPEN
        return new ResilientChatDecorator(List.of(bad, good), 1000L, 1, 30, 1);
    }

    @Test
    void 非系统管理员拒绝() {
        AuthContext.set(USER);
        assertThatThrownBy(() -> service.health()).isInstanceOf(BusinessException.class);
    }

    @Test
    void 聚合_db存储计数与熔断现态() {
        AuthContext.set(ADMIN);
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(1);
        // fileStorage 为 mock(LocalFileStorage)：instanceof 命中本地分支
        var docMapper = mock(KbDocumentMapper.class);
        var kbMapper = mock(KbKnowledgeBaseMapper.class);
        var userMapper = mock(AuthUserMapper.class);
        var agentRunMapper = mock(AgentRunMapper.class);
        when(docMapper.selectCount(any())).thenReturn(3L);
        when(kbMapper.selectCount(any())).thenReturn(2L);
        when(userMapper.selectCount(any())).thenReturn(5L);
        when(agentRunMapper.selectCount(any())).thenReturn(0L);
        service = new AdminHealthService(jdbcTemplate, storageProps, fileStorage,
                chatClient, docMapper, kbMapper, userMapper, agentRunMapper);

        // 候选 1 熔断 OPEN：真实现连续失败 1 次即开（threshold=1）
        forceBreakerOpen();

        AdminHealthService.HealthView view = service.health();

        assertThat(view.backend()).isEqualTo("UP");
        assertThat(view.db().get("up")).isEqualTo(true);
        assertThat((String) view.storage().get("type")).isEqualTo("local");
        assertThat(view.storage().get("writable")).isEqualTo(true);
        assertThat((Long) view.storage().get("usageBytes")).isGreaterThanOrEqualTo(0L);
        assertThat(view.counts().get("docs")).isEqualTo(3L);
        assertThat(view.counts().get("kbs")).isEqualTo(2L);
        assertThat(view.counts().get("users")).isEqualTo(5L);
        // 熔断现态：primary OPEN / fallback CLOSED（真实状态机现态，非猜测）
        assertThat(view.providers()).hasSize(2);
        assertThat(view.providers().get(0).circuitState()).isEqualTo("OPEN");
        assertThat(view.providers().get(1).circuitState()).isEqualTo("CLOSED");
    }

    @Test
    void db异常时如实上报down() {
        AuthContext.set(ADMIN);
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenThrow(new IllegalStateException("db gone"));

        AdminHealthService.HealthView view = service.health();
        assertThat(view.db().get("up")).isEqualTo(false);
    }

    private void forceBreakerOpen() {
        com.zhixing.infra.llm.dto.ChatRequest request = com.zhixing.infra.llm.dto.ChatRequest.builder().build();
        CollectingCallback sink = new CollectingCallback();
        chatClient.stream(request, sink);
    }

    /** 空实现回调（只用于触发候选 1 的可降级失败）。 */
    private static final class CollectingCallback implements com.zhixing.infra.llm.StreamCallback {
        @Override
        public void onContent(String delta) {
        }

        @Override
        public void onThinking(String delta) {
        }

        @Override
        public void onComplete(String finishReason, com.zhixing.infra.llm.dto.ChatUsage usage) {
        }

        @Override
        public void onError(Throwable t) {
        }
    }
}
