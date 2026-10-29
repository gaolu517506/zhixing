package com.zhixing.service;

import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.config.DemoProperties;
import com.zhixing.dao.entity.KbKnowledgeBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 首启演示库种子（F8 后端半，02-技术方案 §B8）：从 classpath 内置演示文档
 * （resources/demo/，内容自备——docs/ 不入库铁律）建库并异步入库。
 * setup 空表语义由线 A 承担，本服务只负责种子本身；建库人身份由调用方传入
 * （setup 处于认证白名单，无请求级 AuthContext），种子过程临时注入后清除。
 */
@Slf4j
@Service
public class DemoSeedService {

    private final KbService kbService;

    private final DemoProperties props;

    public DemoSeedService(KbService kbService, DemoProperties props) {
        this.kbService = kbService;
        this.props = props;
    }

    /** 向导第二选项是否可用：开关开且 classpath 种子文档实际存在（诚实呈现，不开空头支票）。 */
    public boolean seedAvailable() {
        if (!props.isSeedEnabled()) {
            return false;
        }
        try {
            return loadDemoResources().size() > 0;
        } catch (IOException e) {
            log.warn("演示种子资源枚举失败: {}", e.getMessage());
            return false;
        }
    }

    /** 建演示库并上传种子文档（入库流水线异步行进）。返回库 ID。 */
    public long seedLibrary(CurrentUser owner) {
        if (!seedAvailable()) {
            throw new IllegalStateException("演示种子不可用（seed-enabled 关闭或资源缺失）");
        }
        List<Resource> resources;
        try {
            resources = loadDemoResources();
        } catch (IOException e) {
            throw new IllegalStateException("演示种子资源枚举失败", e);
        }
        AuthContext.set(owner);
        try {
            KbKnowledgeBase kb = kbService.createKb(props.getKbName(), "首启演示库（内置示例文档）");
            int uploaded = 0;
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName == null || fileName.isBlank()) {
                    continue;
                }
                try {
                    MultipartFile file = new BytesMultipartFile(fileName, resource.getContentAsByteArray());
                    kbService.upload(kb.getId(), file);
                    uploaded++;
                } catch (IOException e) {
                    // 单个种子文档失败不阻断整库播种（可用文档先入库）
                    log.warn("演示文档读取失败 {}：{}", fileName, e.getMessage());
                }
            }
            log.info("演示库播种完成 kbId={} docs={}", kb.getId(), uploaded);
            return kb.getId();
        } finally {
            AuthContext.clear();
        }
    }

    /** classpath 种子资源枚举（protected 供测试覆写注入假资源）。 */
    protected List<Resource> loadDemoResources() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(props.getDocsPattern());
        List<Resource> docs = new ArrayList<>();
        for (Resource resource : resources) {
            if (resource.isReadable() && resource.getFilename() != null
                    && resource.getFilename().contains(".")) {
                docs.add(resource);
            }
        }
        return docs;
    }
}
