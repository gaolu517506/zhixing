package com.zhixing.controller;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.TestAuth;
import com.zhixing.dao.entity.IntentRoute;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.IntentRouteMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.mapper.AuthSessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 线 G 意图路由端点矩阵（02 §B10，任务书 34-§3）：routes 五端点非系统 ADMIN 一律
 * 403/A0006；未认证 401；系统管理员可达 + 试测面板三态之一真实可见（KEYWORD 真跑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminRoutesMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthUserMapper authUserMapper;

    @Autowired
    private AuthSessionMapper authSessionMapper;

    @Autowired
    private IntentRouteMapper routeMapper;

    @Autowired
    private KbKnowledgeBaseMapper kbMapper;

    private Long kbId;

    private TestAuth.SeededUser admin;

    private TestAuth.SeededUser user;

    private final List<TestAuth.SeededUser> seeded = new ArrayList<>();

    private final List<Long> routeIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        String tag = String.valueOf(System.nanoTime());
        admin = TestAuth.seed(authUserMapper, authSessionMapper, "m3-admin-" + tag, "ADMIN");
        user = TestAuth.seed(authUserMapper, authSessionMapper, "m3-user-" + tag, "USER");
        seeded.add(admin);
        seeded.add(user);
    }

    @AfterEach
    void cleanup() {
        for (TestAuth.SeededUser u : seeded) {
            TestAuth.cleanup(authUserMapper, authSessionMapper, u);
        }
        seeded.clear();
        for (Long id : routeIds) {
            routeMapper.deleteById(id);
        }
        routeIds.clear();
    }

    private long seedRoute(String name) {
        // 目标库用自建真库行（悬空 ID 会因 D19 权限交集被过滤成回落，矩阵需 KEYWORD 真跑）
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(IdUtil.getSnowflakeNextId());
        kb.setName("路由矩阵库-" + System.nanoTime());
        kb.setVisibility("PRIVATE");
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kbMapper.insert(kb);
        kbId = kb.getId();

        IntentRoute entity = new IntentRoute();
        entity.setId(IdUtil.getSnowflakeNextId());
        entity.setName(name);
        entity.setKeywords("[\"保修\"]");
        entity.setExamples("[]");
        entity.setTargetKbIds("[" + kbId + "]");
        entity.setEnabled(true);
        entity.setCreatedAt(LocalDateTime.now());
        routeMapper.insert(entity);
        routeIds.add(entity.getId());
        return entity.getId();
    }

    @Test
    void 路由端点_普通用户全403() throws Exception {
        expect403AsUser(get("/api/admin/routes"));
        expect403AsUser(post("/api/admin/routes").contentType("application/json")
                .content("{\"name\":\"x\",\"keywords\":[\"k\"],\"targetKbIds\":[1]}"));
        expect403AsUser(post("/api/admin/routes/test").contentType("application/json")
                .content("{\"query\":\"保修\"}"));
        expect403AsUser(delete("/api/admin/routes/1"));
    }

    @Test
    void 路由端点_未认证401() throws Exception {
        mockMvc.perform(get("/api/admin/routes")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/routes/test").contentType("application/json")
                .content("{\"query\":\"x\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void 路由端点_系统管理员可达_试测KEYWORD真跑() throws Exception {
        long routeId = seedRoute("矩阵-保修咨询-" + System.nanoTime());
        String bearer = TestAuth.bearer(admin);
        mockMvc.perform(get("/api/admin/routes").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray());
        // 试测：关键词「保修」真跑命中（boot 真库规则含本测播种的一条）
        mockMvc.perform(post("/api/admin/routes/test").header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"query\":\"保修几年\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value("KEYWORD"))
                .andExpect(jsonPath("$.data.routedKbIds[0]").value(String.valueOf(kbId)));
        // 悬空目标：试测面板过滤（矩阵规则的 123 库不存在 → 目标为空数组仍成功）
        mockMvc.perform(delete("/api/admin/routes/" + routeId).header("Authorization", bearer))
                .andExpect(status().isOk());
        routeIds.remove(routeId);
    }

    private void expect403AsUser(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        mockMvc.perform(builder.header("Authorization", TestAuth.bearer(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
    }
}
