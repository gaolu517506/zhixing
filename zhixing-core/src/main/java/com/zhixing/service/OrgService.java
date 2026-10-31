package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.Organization;
import com.zhixing.dao.entity.OrgMember;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.mapper.OrganizationMapper;
import com.zhixing.dao.mapper.OrgMemberMapper;
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
 * 组织管理（02-技术方案 §B9，F10 多租户；契约 contracts/m3-org-intent-contracts.ts 线 F 段）。
 * 门禁在 service 入口：系统 ADMIN 全量管理；组织管理员（org_member.role=ADMIN）管理本组织
 * 成员与库归属；POST/DELETE 组织仅系统 ADMIN。组织归属只扩大可见范围，不授库写权限
 * （写语义仍由 PermissionService.requireKbAdmin 的库角色决定，任务书 33-§1② 裁决）。
 *
 * <p>成员/库归属为 PUT 全量替换差量语义（沿 AdminKbAdminService 先例），差量落
 * ORG_CHANGE 审计（detail 记 from→to）；无变更不记事件。删除组织：有成员或归属库时拒绝（防孤儿）。
 */
@Slf4j
@Service
public class OrgService {

    private static final String ROLE_ORG_ADMIN = "ADMIN";

    private static final String ROLE_ORG_MEMBER = "MEMBER";

    private final OrganizationMapper orgMapper;

    private final OrgMemberMapper orgMemberMapper;

    private final KbKnowledgeBaseMapper kbMapper;

    private final AuthUserMapper authUserMapper;

    private final AuditRecorder audit;

    private final ObjectMapper objectMapper;

    public OrgService(OrganizationMapper orgMapper, OrgMemberMapper orgMemberMapper,
                      KbKnowledgeBaseMapper kbMapper, AuthUserMapper authUserMapper,
                      AuditRecorder audit, ObjectMapper objectMapper) {
        this.orgMapper = orgMapper;
        this.orgMemberMapper = orgMemberMapper;
        this.kbMapper = kbMapper;
        this.authUserMapper = authUserMapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    // ===== 视图/请求 =====

    public record OrgView(Long id, String name, LocalDateTime createdAt) {
    }

    public record OrgSummaryView(Long id, String name, LocalDateTime createdAt,
                                 Long memberCount, Long kbCount) {
    }

    public record OrgMemberView(Long userId, String username, String role) {
    }

    public record KbOrgView(Long kbId, String kbName, String visibility, Long orgId) {
    }

    public record OrgMembershipView(Long id, String name, String role) {
    }

    public record CreateOrgRequest(String name) {
    }

    public record PutMembersRequest(List<MemberUpsert> members) {
    }

    public record MemberUpsert(Long userId, String role) {
    }

    public record PutKbsRequest(List<Long> kbIds) {
    }

    // ===== 组织 CRUD =====

    /** 组织列表（带规模计数；仅系统 ADMIN）。 */
    public List<OrgSummaryView> list() {
        requireSystemAdmin();
        return orgMapper.selectList(new LambdaQueryWrapper<Organization>()
                        .orderByDesc(Organization::getCreatedAt)
                        .orderByDesc(Organization::getId))
                .stream().map(this::toSummary).toList();
    }

    /** 新建组织（仅系统 ADMIN；名称唯一，重名拒绝）。 */
    public OrgView create(CreateOrgRequest request) {
        requireSystemAdmin();
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "组织名不能为空且不超过 64 字符");
        }
        if (orgMapper.selectCount(new LambdaQueryWrapper<Organization>()
                .eq(Organization::getName, name)) > 0) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "组织名已存在：" + name);
        }
        Organization org = new Organization();
        org.setId(IdUtil.getSnowflakeNextId());
        org.setName(name);
        org.setCreatedAt(LocalDateTime.now());
        orgMapper.insert(org);
        log.info("管理员 {} 创建组织 {}（{}）", AuthContext.current().username(), name, org.getId());
        return new OrgView(org.getId(), org.getName(), org.getCreatedAt());
    }

    /** 删除组织（仅系统 ADMIN；有成员或归属库时拒绝——防孤儿引用）。 */
    @Transactional
    public void delete(long orgId) {
        requireSystemAdmin();
        Organization org = requireOrg(orgId);
        Long memberCount = orgMemberMapper.selectCount(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId));
        if (memberCount > 0) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "组织仍有 " + memberCount + " 名成员，先移除后再删除");
        }
        Long kbCount = kbMapper.selectCount(new LambdaQueryWrapper<KbKnowledgeBase>()
                .eq(KbKnowledgeBase::getOrgId, orgId));
        if (kbCount > 0) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "组织仍归属 " + kbCount + " 个知识库，先移除归属后再删除");
        }
        orgMapper.deleteById(orgId);
        log.info("管理员 {} 删除组织 {}（{}）", AuthContext.current().username(), org.getName(), orgId);
    }

    // ===== 成员管理 =====

    public List<OrgMemberView> members(long orgId) {
        requireSystemAdminOrOrgAdmin(orgId);
        requireOrg(orgId);
        List<OrgMember> rows = orgMemberMapper.selectList(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId)
                .orderByAsc(OrgMember::getCreatedAt));
        Map<Long, String> usernames = usernamesOf(rows.stream().map(OrgMember::getUserId).toList());
        return rows.stream()
                .map(m -> new OrgMemberView(m.getUserId(), usernames.get(m.getUserId()), m.getRole()))
                .toList();
    }

    /** PUT 全量替换成员（差量 ADD/ROLE_CHANGE/REMOVE，一条 ORG_CHANGE 汇总 from→to）。 */
    @Transactional
    public List<OrgMemberView> putMembers(long orgId, PutMembersRequest request) {
        CurrentUser caller = requireSystemAdminOrOrgAdmin(orgId);
        requireOrg(orgId);
        List<MemberUpsert> target = request.members() == null ? List.of() : request.members();
        Map<Long, String> targetRoles = new HashMap<>();
        for (MemberUpsert upsert : target) {
            if (upsert == null || upsert.userId() == null) {
                throw new BusinessException(ErrorCode.CLIENT_ERROR, "members[].userId 不能为空");
            }
            String role = upsert.role() == null ? ROLE_ORG_MEMBER : upsert.role().trim().toUpperCase();
            if (!ROLE_ORG_ADMIN.equals(role) && !ROLE_ORG_MEMBER.equals(role)) {
                throw new BusinessException(ErrorCode.CLIENT_ERROR, "role 仅支持 ADMIN / MEMBER");
            }
            if (targetRoles.putIfAbsent(upsert.userId(), role) != null) {
                throw new BusinessException(ErrorCode.CLIENT_ERROR, "members 中 userId 重复：" + upsert.userId());
            }
            if (authUserMapper.selectById(upsert.userId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在：" + upsert.userId());
            }
        }

        Map<Long, OrgMember> existing = new HashMap<>();
        orgMemberMapper.selectList(new LambdaQueryWrapper<OrgMember>().eq(OrgMember::getOrgId, orgId))
                .forEach(m -> existing.put(m.getUserId(), m));

        List<Map<String, Object>> changes = new ArrayList<>();
        for (Map.Entry<Long, String> entry : targetRoles.entrySet()) {
            OrgMember current = existing.get(entry.getKey());
            if (current == null) {
                OrgMember member = new OrgMember();
                member.setOrgId(orgId);
                member.setUserId(entry.getKey());
                member.setRole(entry.getValue());
                member.setCreatedAt(LocalDateTime.now());
                orgMemberMapper.insert(member);
                changes.add(memberChange("ADD", entry.getKey(), null, entry.getValue()));
            } else if (!entry.getValue().equals(current.getRole())) {
                // 复合主键（org_id,user_id）无单 @TableId——updateById 未注册（验收抓获），
                // 改条件更新；条件带 orgId 防跨组织误改
                OrgMember patch = new OrgMember();
                patch.setRole(entry.getValue());
                orgMemberMapper.update(patch, new LambdaQueryWrapper<OrgMember>()
                        .eq(OrgMember::getOrgId, orgId)
                        .eq(OrgMember::getUserId, entry.getKey()));
                changes.add(memberChange("ROLE_CHANGE", entry.getKey(), current.getRole(), entry.getValue()));
            }
        }
        for (OrgMember current : existing.values()) {
            if (!targetRoles.containsKey(current.getUserId())) {
                orgMemberMapper.delete(new LambdaQueryWrapper<OrgMember>()
                        .eq(OrgMember::getOrgId, orgId)
                        .eq(OrgMember::getUserId, current.getUserId()));
                changes.add(memberChange("REMOVE", current.getUserId(), current.getRole(), null));
            }
        }

        if (!changes.isEmpty()) {
            audit.record(caller.id(), caller.username(), AuditAction.ORG_CHANGE.name(),
                    "ORG", String.valueOf(orgId), AuditResult.OK, orgChangeDetailJson(orgId, "members", changes));
        }
        return members(orgId);
    }

    // ===== 库归属管理 =====

    /** 该组织归属的库（含可见性；归属与可见性是两个正交维度——private 归属库仅本组织可见）。 */
    public List<KbOrgView> listKbs(long orgId) {
        requireSystemAdminOrOrgAdmin(orgId);
        requireOrg(orgId);
        return kbMapper.selectList(new LambdaQueryWrapper<KbKnowledgeBase>()
                .eq(KbKnowledgeBase::getOrgId, orgId)
                .orderByDesc(KbKnowledgeBase::getCreatedAt))
                .stream()
                .map(kb -> new KbOrgView(kb.getId(), kb.getName(), kb.getVisibility(), kb.getOrgId()))
                .toList();
    }

    /** PUT 全量替换库归属（差量 ASSIGN/UNASSIGN，一条 ORG_CHANGE 汇总）。 */
    @Transactional
    public void putKbs(long orgId, PutKbsRequest request) {
        CurrentUser caller = requireSystemAdminOrOrgAdmin(orgId);
        requireOrg(orgId);
        List<Long> targetIds = request.kbIds() == null ? List.of() : request.kbIds();
        for (Long kbId : targetIds) {
            if (kbId == null || kbMapper.selectById(kbId) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在：" + kbId);
            }
        }

        Map<Long, String> existing = new HashMap<>();
        kbMapper.selectList(new LambdaQueryWrapper<KbKnowledgeBase>().eq(KbKnowledgeBase::getOrgId, orgId))
                .forEach(kb -> existing.put(kb.getId(), kb.getName()));

        List<Map<String, Object>> changes = new ArrayList<>();
        for (Long kbId : targetIds) {
            if (!existing.containsKey(kbId)) {
                KbKnowledgeBase patch = new KbKnowledgeBase();
                patch.setId(kbId);
                patch.setOrgId(orgId);
                kbMapper.updateById(patch);
                changes.add(kbChange("ASSIGN", kbId, existing.get(kbId)));
            }
        }
        for (Map.Entry<Long, String> entry : existing.entrySet()) {
            if (!targetIds.contains(entry.getKey())) {
                KbKnowledgeBase patch = new KbKnowledgeBase();
                patch.setId(entry.getKey());
                patch.setOrgId(null);
                kbMapper.updateById(patch);
                changes.add(kbChange("UNASSIGN", entry.getKey(), entry.getValue()));
            }
        }

        if (!changes.isEmpty()) {
            audit.record(caller.id(), caller.username(), AuditAction.ORG_CHANGE.name(),
                    "ORG", String.valueOf(orgId), AuditResult.OK, orgChangeDetailJson(orgId, "kbAssignments", changes));
        }
    }

    // ===== me 组装（additive）=====

    /** 当前用户所属组织（顶栏徽标与 /kb 筛选数据源；未加入任何组织为空列表）。 */
    public List<OrgMembershipView> membershipsOf(long userId) {
        List<OrgMember> rows = orgMemberMapper.selectList(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getUserId, userId)
                .orderByAsc(OrgMember::getCreatedAt));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = new HashMap<>();
        orgMapper.selectBatchIds(rows.stream().map(OrgMember::getOrgId).toList())
                .forEach(org -> names.put(org.getId(), org.getName()));
        return rows.stream()
                .map(m -> new OrgMembershipView(m.getOrgId(), names.get(m.getOrgId()), m.getRole()))
                .toList();
    }

    // ===== 门禁 =====

    private CurrentUser requireSystemAdmin() {
        CurrentUser caller = AuthContext.current();
        if (!caller.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅系统管理员可管理组织");
        }
        return caller;
    }

    /** 系统 ADMIN ∪ 本组织管理员（02 §B9：组织管理员管本组织成员与库归属）。 */
    private CurrentUser requireSystemAdminOrOrgAdmin(long orgId) {
        CurrentUser caller = AuthContext.current();
        if (caller.isAdmin()) {
            return caller;
        }
        OrgMember membership = orgMemberMapper.selectOne(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId)
                .eq(OrgMember::getUserId, caller.id())
                .eq(OrgMember::getRole, ROLE_ORG_ADMIN));
        if (membership == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅系统管理员或本组织管理员可执行该操作");
        }
        return caller;
    }

    // ===== 内部 =====

    private Organization requireOrg(long orgId) {
        Organization org = orgMapper.selectById(orgId);
        if (org == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "组织不存在：" + orgId);
        }
        return org;
    }

    private OrgSummaryView toSummary(Organization org) {
        Long memberCount = orgMemberMapper.selectCount(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, org.getId()));
        Long kbCount = kbMapper.selectCount(new LambdaQueryWrapper<KbKnowledgeBase>()
                .eq(KbKnowledgeBase::getOrgId, org.getId()));
        return new OrgSummaryView(org.getId(), org.getName(), org.getCreatedAt(), memberCount, kbCount);
    }

    private Map<Long, String> usernamesOf(List<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            authUserMapper.selectBatchIds(userIds)
                    .forEach(u -> names.put(u.getId(), u.getUsername()));
        }
        return names;
    }

    private Map<String, Object> memberChange(String op, Long userId, String from, String to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("op", op);
        change.put("userId", userId);
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    private Map<String, Object> kbChange(String op, Long kbId, String kbName) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("op", op);
        change.put("kbId", kbId);
        change.put("kbName", kbName);
        return change;
    }

    private String orgChangeDetailJson(long orgId, String scope, List<Map<String, Object>> changes) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("orgId", orgId);
            map.put("scope", scope);
            map.put("changes", changes);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }
}
