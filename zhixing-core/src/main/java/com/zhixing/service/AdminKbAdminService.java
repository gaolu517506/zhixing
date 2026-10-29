package com.zhixing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.KbMember;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.KbMemberMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库权限管理（02-技术方案 §B7，F7）：系统 ADMIN 后台的成员与可见性管理。
 * 变更全量接线 PERMISSION_CHANGE 审计（增删成员/改角色/改 visibility 各一条，
 * detail 记 from→to）。注意：这里改的是 kb_member/kb_visibility 数据本身，
 * 判定语义仍收敛在线 A 的 PermissionService（本类只写数据不做判定）。
 */
@Slf4j
@Service
public class AdminKbAdminService {

    private static final String ROLE_ADMIN = "ADMIN";

    private static final String ROLE_MEMBER = "MEMBER";

    private static final String VISIBILITY_PRIVATE = "PRIVATE";

    private static final String VISIBILITY_ALL = "ALL";

    private final KbKnowledgeBaseMapper kbMapper;

    private final KbMemberMapper memberMapper;

    private final AuthUserMapper userMapper;

    private final AuditRecorder audit;

    private final ObjectMapper objectMapper;

    public AdminKbAdminService(KbKnowledgeBaseMapper kbMapper, KbMemberMapper memberMapper,
                               AuthUserMapper userMapper, AuditRecorder audit, ObjectMapper objectMapper) {
        this.kbMapper = kbMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    // ===== 视图/请求 =====

    public record MemberView(Long userId, String username, String role, LocalDateTime createdAt) {
    }

    public record KbPermissionView(Long kbId, String kbName, String visibility, List<MemberView> members) {
    }

    public record MemberUpsert(Long userId, String role) {
    }

    public record PutMembersRequest(List<MemberUpsert> members) {
    }

    public record PatchKbRequest(String visibility) {
    }

    // ===== 查询 =====

    public KbPermissionView members(long kbId) {
        requireSystemAdmin();
        requireKb(kbId);
        List<KbMember> rows = memberMapper.selectList(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId)
                .orderByAsc(KbMember::getCreatedAt));
        Map<Long, String> usernames = usernamesOf(rows.stream().map(KbMember::getUserId).toList());
        List<MemberView> members = rows.stream()
                .map(m -> new MemberView(m.getUserId(), usernames.get(m.getUserId()),
                        m.getRole(), m.getCreatedAt()))
                .toList();
        KbKnowledgeBase kb = kbMapper.selectById(kbId);
        return new KbPermissionView(kbId, kb.getName(), kb.getVisibility(), members);
    }

    // ===== 全量替换成员（PUT 语义：以请求集为准做差量增删改） =====

    @Transactional
    public KbPermissionView putMembers(long kbId, PutMembersRequest request) {
        CurrentUser caller = requireSystemAdmin();
        requireKb(kbId);
        List<MemberUpsert> target = request.members() == null ? List.of() : request.members();
        Map<Long, String> targetRoles = new HashMap<>();
        for (MemberUpsert upsert : target) {
            if (upsert == null || upsert.userId() == null) {
                throw new BusinessException(ErrorCode.CLIENT_ERROR, "members[].userId 不能为空");
            }
            String role = upsert.role() == null ? ROLE_MEMBER : upsert.role().trim().toUpperCase();
            if (!ROLE_ADMIN.equals(role) && !ROLE_MEMBER.equals(role)) {
                throw new BusinessException(ErrorCode.CLIENT_ERROR, "role 仅支持 ADMIN / MEMBER");
            }
            if (targetRoles.putIfAbsent(upsert.userId(), role) != null) {
                throw new BusinessException(ErrorCode.CLIENT_ERROR, "members 中 userId 重复：" + upsert.userId());
            }
            if (userMapper.selectById(upsert.userId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在：" + upsert.userId());
            }
        }

        Map<Long, KbMember> existing = new HashMap<>();
        memberMapper.selectList(new LambdaQueryWrapper<KbMember>().eq(KbMember::getKbId, kbId))
                .forEach(m -> existing.put(m.getUserId(), m));

        List<Map<String, Object>> changes = new ArrayList<>();
        // 新增与改角色
        for (Map.Entry<Long, String> entry : targetRoles.entrySet()) {
            KbMember current = existing.get(entry.getKey());
            if (current == null) {
                KbMember member = new KbMember();
                member.setKbId(kbId);
                member.setUserId(entry.getKey());
                member.setRole(entry.getValue());
                member.setCreatedAt(LocalDateTime.now());
                memberMapper.insert(member);
                changes.add(change("ADD", entry.getKey(), null, entry.getValue()));
            } else if (!entry.getValue().equals(current.getRole())) {
                KbMember patch = new KbMember();
                patch.setKbId(kbId);
                patch.setUserId(entry.getKey());
                patch.setRole(entry.getValue());
                memberMapper.updateById(patch);
                changes.add(change("ROLE_CHANGE", entry.getKey(), current.getRole(), entry.getValue()));
            }
        }
        // 移除（在 existing 不在 target）
        for (KbMember current : existing.values()) {
            if (!targetRoles.containsKey(current.getUserId())) {
                memberMapper.delete(new LambdaQueryWrapper<KbMember>()
                        .eq(KbMember::getKbId, kbId)
                        .eq(KbMember::getUserId, current.getUserId()));
                changes.add(change("REMOVE", current.getUserId(), current.getRole(), null));
            }
        }

        if (!changes.isEmpty()) {
            String detailJson = changeDetailJson(kbId, changes);
            audit.record(caller.id(), caller.username(), AuditAction.PERMISSION_CHANGE.name(),
                    "KB", String.valueOf(kbId), AuditResult.OK, detailJson);
        }
        return members(kbId);
    }

    /** visibility 调整（PRIVATE / ALL）。 */
    public KbPermissionView patchKb(long kbId, PatchKbRequest request) {
        CurrentUser caller = requireSystemAdmin();
        KbKnowledgeBase kb = requireKb(kbId);
        String visibility = request.visibility() == null ? "" : request.visibility().trim().toUpperCase();
        if (!VISIBILITY_PRIVATE.equals(visibility) && !VISIBILITY_ALL.equals(visibility)) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "visibility 仅支持 PRIVATE / ALL");
        }
        String from = kb.getVisibility();
        if (!visibility.equals(from)) {
            KbKnowledgeBase patch = new KbKnowledgeBase();
            patch.setId(kbId);
            patch.setVisibility(visibility);
            kbMapper.updateById(patch);
            String detailJson = visibilityDetailJson(kbId, from, visibility);
            audit.record(caller.id(), caller.username(), AuditAction.PERMISSION_CHANGE.name(),
                    "KB", String.valueOf(kbId), AuditResult.OK, detailJson);
            log.info("管理员 {} 调整库 {} 可见性：{} → {}", caller.username(), kbId, from, visibility);
        }
        return members(kbId);
    }

    // ===== 内部 =====

    private CurrentUser requireSystemAdmin() {
        CurrentUser caller = AuthContext.current();
        if (!caller.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅系统管理员可管理库权限");
        }
        return caller;
    }

    private KbKnowledgeBase requireKb(long kbId) {
        KbKnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ErrorCode.KB_NOT_FOUND);
        }
        return kb;
    }

    private Map<Long, String> usernamesOf(List<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        // selectBatchIds（BaseMapper 内置）：纯 ID 查询无需 lambda 列解析
        userMapper.selectBatchIds(userIds)
                .forEach(u -> names.put(u.getId(), u.getUsername()));
        return names;
    }

    private Map<String, Object> change(String op, Long userId, String from, String to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("op", op);
        change.put("userId", userId);
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    private String changeDetailJson(long kbId, List<Map<String, Object>> changes) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scope", "members");
            map.put("changes", changes);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    private String visibilityDetailJson(long kbId, String from, String to) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scope", "visibility");
            map.put("from", from);
            map.put("to", to);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }
}
