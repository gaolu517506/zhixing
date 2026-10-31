package com.zhixing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.Organization;
import com.zhixing.dao.entity.OrgMember;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.OrganizationMapper;
import com.zhixing.dao.mapper.OrgMemberMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组织管理单测（02 §B9，任务书 33-§3）：门禁矩阵（系统 ADMIN / 组织 ADMIN / 普通用户 /
 * 跨组织 ADMIN）、创建校验与重名、删除保护（防孤儿）、PUT 全量替换差量与 ORG_CHANGE
 * 审计、me 组装 membershipsOf。
 */
class OrgServiceTest {

    private OrganizationMapper orgMapper;

    private OrgMemberMapper orgMemberMapper;

    private KbKnowledgeBaseMapper kbMapper;

    private AuthUserMapper userMapper;

    private AuditRecorder audit;

    private OrgService service;

    private static final CurrentUser ROOT = new CurrentUser(1L, "root", "ADMIN", AuthChannel.WEB);

    private static final CurrentUser PLAIN = new CurrentUser(2L, "member", "USER", AuthChannel.WEB);

    private static final CurrentUser ORG_A_ADMIN = new CurrentUser(3L, "orgA-admin", "USER", AuthChannel.WEB);

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito JVM 无 Spring 装配，LambdaQueryWrapper 需要实体列缓存（生产态由 Mapper 注册初始化）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Organization.class);
        TableInfoHelper.initTableInfo(assistant, OrgMember.class);
        TableInfoHelper.initTableInfo(assistant, KbKnowledgeBase.class);
    }

    @BeforeEach
    void setUp() {
        orgMapper = mock(OrganizationMapper.class);
        orgMemberMapper = mock(OrgMemberMapper.class);
        kbMapper = mock(KbKnowledgeBaseMapper.class);
        userMapper = mock(AuthUserMapper.class);
        audit = mock(AuditRecorder.class);
        service = new OrgService(orgMapper, orgMemberMapper, kbMapper, userMapper, audit, new ObjectMapper());
        AuthContext.set(ROOT);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private Organization org(long id, String name) {
        Organization org = new Organization();
        org.setId(id);
        org.setName(name);
        org.setCreatedAt(LocalDateTime.of(2026, 10, 30, 9, 0));
        return org;
    }

    private void stubOrgAdminMembership(long orgId, long userId) {
        OrgMember membership = new OrgMember();
        membership.setOrgId(orgId);
        membership.setUserId(userId);
        membership.setRole("ADMIN");
        when(orgMemberMapper.selectOne(any())).thenReturn(membership);
    }

    // ===== 门禁矩阵 =====

    @Test
    void 门禁_普通用户全拒绝_组织管理员仅本组织() {
        AuthContext.set(PLAIN);
        assertThatThrownBy(() -> service.list()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(new OrgService.CreateOrgRequest("售后部")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.members(9L)).isInstanceOf(BusinessException.class);

        // 组织 A 管理员：本组织可管理成员/归属，他组织不可
        AuthContext.set(ORG_A_ADMIN);
        stubOrgAdminMembership(9L, ORG_A_ADMIN.id());
        when(orgMapper.selectById(9L)).thenReturn(org(9L, "售后部"));
        assertThat(service.members(9L)).isNotNull();
        when(orgMemberMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.members(8L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(BusinessException.class);
        verify(orgMapper, never()).deleteById(any(Long.class));
    }

    // ===== 创建 =====

    @Test
    void 创建_空名超长与重名拒绝_成功落库() {
        when(orgMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(new OrgService.CreateOrgRequest("售后部")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        assertThatThrownBy(() -> service.create(new OrgService.CreateOrgRequest("  ")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(new OrgService.CreateOrgRequest("x".repeat(65))))
                .isInstanceOf(BusinessException.class);

        when(orgMapper.selectCount(any())).thenReturn(0L);
        OrgService.OrgView view = service.create(new OrgService.CreateOrgRequest("售后部"));
        assertThat(view.name()).isEqualTo("售后部");
        ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
        verify(orgMapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).isNotNull();
    }

    // ===== 删除保护 =====

    @Test
    void 删除_有成员或归属库拒绝_空组织可删() {
        when(orgMapper.selectById(9L)).thenReturn(org(9L, "售后部"));
        when(orgMemberMapper.selectCount(any())).thenReturn(2L);
        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 名成员");

        when(orgMemberMapper.selectCount(any())).thenReturn(0L);
        when(kbMapper.selectCount(any())).thenReturn(3L);
        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3 个知识库");

        when(kbMapper.selectCount(any())).thenReturn(0L);
        service.delete(9L);
        verify(orgMapper).deleteById(9L);
    }

    // ===== PUT 成员差量 =====

    @Test
    void putMembers_差量增删改各落ORG_CHANGE() {
        stubOrgAdminMembership(9L, ORG_A_ADMIN.id());
        when(orgMapper.selectById(9L)).thenReturn(org(9L, "售后部"));
        AuthUser u2 = new AuthUser();
        u2.setId(2L);
        AuthUser u3 = new AuthUser();
        u3.setId(3L);
        when(userMapper.selectById(2L)).thenReturn(u2);
        when(userMapper.selectById(3L)).thenReturn(u3);
        // existing：userId=2 MEMBER（将改 ADMIN）、userId=4 MEMBER（将 REMOVE）
        OrgMember existing2 = new OrgMember();
        existing2.setOrgId(9L);
        existing2.setUserId(2L);
        existing2.setRole("MEMBER");
        OrgMember existing4 = new OrgMember();
        existing4.setOrgId(9L);
        existing4.setUserId(4L);
        existing4.setRole("MEMBER");
        when(orgMemberMapper.selectList(any())).thenReturn(List.of(existing2, existing4));

        service.putMembers(9L, new OrgService.PutMembersRequest(List.of(
                new OrgService.MemberUpsert(2L, "ADMIN"),
                new OrgService.MemberUpsert(3L, "MEMBER"))));

        verify(orgMemberMapper).insert(any(OrgMember.class)); // ADD userId=3
        // ROLE_CHANGE userId=2：复合主键条件更新（验收抓获 updateById 未注册的回归锁）
        verify(orgMemberMapper).update(any(OrgMember.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(orgMemberMapper).delete(any()); // REMOVE userId=4
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), any(), action.capture(), any(), any(), any(), any());
        assertThat(action.getValue()).isEqualTo("ORG_CHANGE");
    }

    @Test
    void putMembers_无变更不记审计_非法角色与重复userId拒绝() {
        stubOrgAdminMembership(9L, ORG_A_ADMIN.id());
        when(orgMapper.selectById(9L)).thenReturn(org(9L, "售后部"));
        AuthUser u2 = new AuthUser();
        u2.setId(2L);
        when(userMapper.selectById(2L)).thenReturn(u2);
        OrgMember existing2 = new OrgMember();
        existing2.setOrgId(9L);
        existing2.setUserId(2L);
        existing2.setRole("MEMBER");
        when(orgMemberMapper.selectList(any())).thenReturn(List.of(existing2));

        // 无变更（目标集=现有集）
        service.putMembers(9L, new OrgService.PutMembersRequest(List.of(
                new OrgService.MemberUpsert(2L, "MEMBER"))));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.putMembers(9L, new OrgService.PutMembersRequest(List.of(
                new OrgService.MemberUpsert(2L, "GUEST"))))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.putMembers(9L, new OrgService.PutMembersRequest(List.of(
                new OrgService.MemberUpsert(2L, "MEMBER"),
                new OrgService.MemberUpsert(2L, "ADMIN"))))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.putMembers(9L, new OrgService.PutMembersRequest(List.of(
                new OrgService.MemberUpsert(99L, "MEMBER"))))).isInstanceOf(BusinessException.class);
    }

    // ===== PUT 库归属差量 =====

    @Test
    void putKbs_差量ASSIGN_UNASSIGN落ORG_CHANGE_不存在库拒绝() {
        stubOrgAdminMembership(9L, ORG_A_ADMIN.id());
        when(orgMapper.selectById(9L)).thenReturn(org(9L, "售后部"));
        KbKnowledgeBase kb1 = new KbKnowledgeBase();
        kb1.setId(11L);
        kb1.setName("售后手册库");
        kb1.setOrgId(9L);
        when(kbMapper.selectById(11L)).thenReturn(kb1);
        KbKnowledgeBase kb2 = new KbKnowledgeBase();
        kb2.setId(12L);
        kb2.setName("售后话术库");
        when(kbMapper.selectById(12L)).thenReturn(kb2);
        when(kbMapper.selectList(any())).thenReturn(List.of(kb1));

        // 目标：11（existing 保留，无变更）+ 12（新增 ASSIGN）→ 仅 12 一次 updateById
        service.putKbs(9L, new OrgService.PutKbsRequest(List.of(11L, 12L)));
        verify(kbMapper, org.mockito.Mockito.times(1)).updateById(any(KbKnowledgeBase.class));
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), any(), action.capture(), any(), any(), any(), any());
        assertThat(action.getValue()).isEqualTo("ORG_CHANGE");

        assertThatThrownBy(() -> service.putKbs(9L, new OrgService.PutKbsRequest(List.of(999L))))
                .isInstanceOf(BusinessException.class);
    }

    // ===== me 组装 =====

    @Test
    void membershipsOf_用户所属组织组装_空返回空列表() {
        OrgMember membership = new OrgMember();
        membership.setOrgId(9L);
        membership.setUserId(2L);
        membership.setRole("MEMBER");
        when(orgMemberMapper.selectList(any())).thenReturn(List.of(membership));
        Organization org = org(9L, "售后部");
        when(orgMapper.selectBatchIds(any())).thenReturn(List.of(org));

        List<OrgService.OrgMembershipView> views = service.membershipsOf(2L);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).name()).isEqualTo("售后部");
        assertThat(views.get(0).role()).isEqualTo("MEMBER");

        when(orgMemberMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.membershipsOf(2L)).isEmpty();
    }
}
