package com.zhixing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.config.StorageProperties;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.storage.LocalFileStorage;
import com.zhixing.infra.storage.MinioFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统健康聚合（02-技术方案 §B7，F7）：仅系统 ADMIN。{backend, db, storage, providers, counts}——
 * 供应商状态**读熔断器现态**（CLOSED/OPEN/HALF_OPEN），默认不主动 ping（省费用）；
 * DB ping 是轻量 SELECT 1；存储探测只做本地临时文件 / MinIO bucketExists 这类廉价检查。
 *
 * <p>熔断态读取说明：zhixing-llm 模块未暴露熔断器状态的公开 API 且本线禁改该模块
 * （任务书红线），故对 {@link ResilientChatDecorator} 的 breaker 数组做**只读反射**；
 * llm 模块日后提供公开查询口时应替换掉这段反射（此处集中封装便于替换）。
 */
@Slf4j
@Service
public class AdminHealthService {

    private final JdbcTemplate jdbcTemplate;

    private final StorageProperties storageProps;

    private final com.zhixing.infra.storage.FileStorage fileStorage;

    private final ChatClient chatClient;

    private final KbDocumentMapper docMapper;

    private final KbKnowledgeBaseMapper kbMapper;

    private final AuthUserMapper userMapper;

    private final AgentRunMapper agentRunMapper;

    public AdminHealthService(JdbcTemplate jdbcTemplate, StorageProperties storageProps,
                              com.zhixing.infra.storage.FileStorage fileStorage, ChatClient chatClient,
                              KbDocumentMapper docMapper, KbKnowledgeBaseMapper kbMapper,
                              AuthUserMapper userMapper, AgentRunMapper agentRunMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageProps = storageProps;
        this.fileStorage = fileStorage;
        this.chatClient = chatClient;
        this.docMapper = docMapper;
        this.kbMapper = kbMapper;
        this.userMapper = userMapper;
        this.agentRunMapper = agentRunMapper;
    }

    public record ProviderState(String name, String circuitState) {
    }

    public record HealthView(String backend, Map<String, Object> db, Map<String, Object> storage,
                             List<ProviderState> providers, Map<String, Object> counts) {
    }

    public HealthView health() {
        requireSystemAdmin();

        Map<String, Object> db = dbHealth();
        Map<String, Object> storage = storageHealth();
        List<ProviderState> providers = providerStates();
        Map<String, Object> counts = counts();

        return new HealthView("UP", db, storage, providers, counts);
    }

    /** DB ping：轻量 SELECT 1（常量 SQL，无外部输入）。 */
    private Map<String, Object> dbHealth() {
        Map<String, Object> db = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            db.put("up", true);
            db.put("pingMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            db.put("up", false);
            db.put("pingMs", System.currentTimeMillis() - start);
            db.put("error", e.getClass().getSimpleName());
            log.warn("健康检查 DB ping 失败: {}", e.getMessage());
        }
        return db;
    }

    private Map<String, Object> storageHealth() {
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("type", storageProps.getType());
        if (fileStorage instanceof LocalFileStorage) {
            Path root = Path.of(storageProps.getLocalRoot()).toAbsolutePath().normalize();
            storage.put("writable", probeLocalWritable(root));
            storage.put("usageBytes", dirSizeOrNull(root));
        } else if (fileStorage instanceof MinioFileStorage) {
            // MinIO：bucketExists 作为可达性代理；用量统计不在此展开（对象存储侧运维指标）
            storage.put("writable", minioBucketExists());
            storage.put("usageBytes", null);
        } else {
            storage.put("writable", null);
            storage.put("usageBytes", null);
        }
        return storage;
    }

    private boolean probeLocalWritable(Path root) {
        try {
            Files.createDirectories(root);
            Path probe = root.resolve(".health-probe-" + ProcessHandle.current().pid());
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            log.warn("健康检查本地存储不可写 {}: {}", root, e.getMessage());
            return false;
        }
    }

    private Long dirSizeOrNull(Path root) {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return null;
        }
    }

    /** MinIO 可达性（bucketExists）：反射取 MinioClient，llm 同款红线说明——集中一处便于替换。 */
    private Boolean minioBucketExists() {
        try {
            Field clientField = MinioFileStorage.class.getDeclaredField("client");
            clientField.setAccessible(true);
            Object client = clientField.get(fileStorage);
            Field bucketField = MinioFileStorage.class.getDeclaredField("bucket");
            bucketField.setAccessible(true);
            String bucket = String.valueOf(bucketField.get(fileStorage));
            Object builder = Class.forName("io.minio.BucketExistsArgs")
                    .getMethod("builder").invoke(null);
            builder.getClass().getMethod("bucket", String.class).invoke(builder, bucket);
            // SDK 签名 bucketExists(BucketExistsArgs)——builder 必须 build()（DL-29 冒烟修复：原直传 Builder NoSuchMethod 被吞成 null）
            Object args = builder.getClass().getMethod("build").invoke(builder);
            return (Boolean) client.getClass()
                    .getMethod("bucketExists", args.getClass())
                    .invoke(client, args);
        } catch (Exception e) {
            log.warn("健康检查 MinIO 探测失败: {}", e.getMessage());
            return null;
        }
    }

    /** 候选链熔断现态（只读反射，见类注释说明）；候选 1 = primary，候选 2 = fallback。 */
    private List<ProviderState> providerStates() {
        List<ProviderState> states = new ArrayList<>();
        try {
            if (!(chatClient instanceof com.zhixing.infra.llm.ResilientChatDecorator decorator)) {
                return states;
            }
            Field breakersField = com.zhixing.infra.llm.ResilientChatDecorator.class.getDeclaredField("breakers");
            breakersField.setAccessible(true);
            Object[] breakers = (Object[]) breakersField.get(decorator);
            for (Object breaker : breakers) {
                Field nameField = breaker.getClass().getDeclaredField("name");
                nameField.setAccessible(true);
                Field stateField = breaker.getClass().getDeclaredField("state");
                stateField.setAccessible(true);
                String name = String.valueOf(nameField.get(breaker));
                String state = String.valueOf(stateField.get(breaker));
                states.add(new ProviderState(name, state));
            }
        } catch (Exception e) {
            log.warn("健康检查读取熔断器现态失败（llm 模块结构变更？）: {}", e.getMessage());
        }
        return states;
    }

    private Map<String, Object> counts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("docs", docMapper.selectCount(null));
        counts.put("kbs", kbMapper.selectCount(null));
        counts.put("users", userMapper.selectCount(null));
        counts.put("agentRuns", agentRunMapper.selectCount(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getStatus, "RUNNING")));
        return counts;
    }

    private CurrentUser requireSystemAdmin() {
        CurrentUser caller = AuthContext.current();
        if (!caller.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅系统管理员可查看系统健康");
        }
        return caller;
    }
}
