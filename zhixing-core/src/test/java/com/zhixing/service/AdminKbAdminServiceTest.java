package com.zhixing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.KbMember;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.KbMemberMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库权限管理单测（02 §B7 测试点）：非 ADMIN 403、PUT 全量替换差量（ADD/ROLE_CHANGE/REMOVE）、
 * PERMISSION_CHANGE 审计接线、visibility 变更审计与无变更不记事件。
 */
class AdminKbAdminServiceTest {

    private KbKnowledgeBaseMapper kbMapper;

    private KbMemberMapper memberMapper;

    private AuthUserMapper userMapper;

    private AuditRecorder audit;

    private AdminKbAdminService service;

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", "ADMIN", AuthChannel.WEB);

    private static final CurrentUser USER = new CurrentUser(2L, "member", "USER", AuthChannel.WEB);

    @BeforeEach
    void setUp() {
        kbMapper = mock(KbKnowledgeBaseMapper.class);
        memberMapper = mock(KbMemberMapper.class);
        userMapper = mock(AuthUserMapper.class);
        audit = mock(AuditRecorder.class);
        service = new AdminKbAdminService(kbMapper, memberMapper, userMapper, audit, new ObjectMapper());
        AuthContext.set(ADMIN);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private void stubKb(long kbId, String visibility) {
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(kbId);
        kb.setName("库");
        kb.setVisibility(visibility);
        when(kbMapper.selectById(kbId)).thenReturn(kb);
    }

    private KbMember member(long userId, String role) {
        KbMember m = new KbMember();
        m.setKbId(9L);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    @Test
    void 非系统管理员拒绝() {
        AuthContext.set(USER);
        assertThatThrownBy(() -> service.members(9L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.putMembers(9L,
                new AdminKbAdminService.PutMembersRequest(List.of()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.patchKb(9L,
                new AdminKbAdminService.PatchKbRequest("ALL"))).isInstanceOf(BusinessException.class);
    }

    @Test
    void putMembers_差量增删改各落一条PERMISSION_CHANGE() {
        stubKb(9L, "PRIVATE");
        when(userMapper.selectById(11L)).thenReturn(new com.zhixing.dao.entity.AuthUser());
        when(userMapper.selectById(12L)).thenReturn(new com.zhixing.dao.entity.AuthUser());
        // 存量：12=MEMBER（将改 ADMIN）、13=MEMBER（将移除）；11 为新增
        when(memberMapper.selectList(any())).thenReturn(List.of(member(12L, "MEMBER"), member(13L, "MEMBER")));
        when(userMapper.selectList(any())).thenReturn(List.of());

        service.putMembers(9L, new AdminKbAdminService.PutMembersRequest(List.of(
                new AdminKbAdminService.MemberUpsert(11L, "MEMBER"),
                new AdminKbAdminService.MemberUpsert(12L, "ADMIN"))));

        verify(memberMapper).insert(any(KbMember.class));
        verify(memberMapper).updateById(any(KbMember.class));
        verify(memberMapper).delete(any());
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), any(), action.capture(), any(), any(), any(), any());
        assertThat(action.getValue()).isEqualTo("PERMISSION_CHANGE");
    }

    @Test
    void putMembers_无变更不记审计() {
        stubKb(9L, "PRIVATE");
        when(memberMapper.selectList(any())).thenReturn(List.of(member(11L, "MEMBER")));
        when(userMapper.selectById(11L)).thenReturn(new com.zhixing.dao.entity.AuthUser());
        when(userMapper.selectList(any())).thenReturn(List.of());

        service.putMembers(9L, new AdminKbAdminService.PutMembersRequest(List.of(
                new AdminKbAdminService.MemberUpsert(11L, "MEMBER"))));

        verify(memberMapper, never()).insert(any(KbMember.class));
        verify(memberMapper, never()).updateById(any(KbMember.class));
        verify(memberMapper, never()).delete(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void putMembers_重复userId与未知角色拒绝() {
        stubKb(9L, "PRIVATE");
        when(userMapper.selectById(11L)).thenReturn(new com.zhixing.dao.entity.AuthUser());
        assertThatThrownBy(() -> service.putMembers(9L, new AdminKbAdminService.PutMembersRequest(List.of(
                new AdminKbAdminService.MemberUpsert(11L, "MEMBER"),
                new AdminKbAdminService.MemberUpsert(11L, "ADMIN")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> service.putMembers(9L, new AdminKbAdminService.PutMembersRequest(List.of(
                new AdminKbAdminService.MemberUpsert(11L, "OWNER")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("role");
    }

    @Test
    void visibility变更落审计_无变更不记() {
        stubKb(9L, "PRIVATE");
        when(memberMapper.selectList(any())).thenReturn(List.of());

        service.patchKb(9L, new AdminKbAdminService.PatchKbRequest("ALL"));

        ArgumentCaptor<KbKnowledgeBase> captor = ArgumentCaptor.forClass(KbKnowledgeBase.class);
        verify(kbMapper).updateById(captor.capture());
        assertThat(captor.getValue().getVisibility()).isEqualTo("ALL");
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), any(), action.capture(), any(), any(), any(), any());
        assertThat(action.getValue()).isEqualTo("PERMISSION_CHANGE");

        // 无变更（ALL → ALL）：不落库不审计
        org.mockito.Mockito.clearInvocations(kbMapper, audit);
        stubKb(9L, "ALL");
        service.patchKb(9L, new AdminKbAdminService.PatchKbRequest("ALL"));
        verify(kbMapper, never()).updateById(any(KbKnowledgeBase.class));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void visibility非法值拒绝() {
        stubKb(9L, "PRIVATE");
        assertThatThrownBy(() -> service.patchKb(9L, new AdminKbAdminService.PatchKbRequest("PUBLIC")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("visibility");
    }

    @Test
    void listAllKbs_系统ADMIN全库列表_字段映射完整() {
        KbKnowledgeBase first = new KbKnowledgeBase();
        first.setId(11L);
        first.setName("演示库");
        first.setVisibility("ALL");
        first.setCreatedAt(java.time.LocalDateTime.of(2026, 10, 29, 12, 0));
        KbKnowledgeBase second = new KbKnowledgeBase();
        second.setId(12L);
        second.setName("私库");
        second.setVisibility("PRIVATE");
        second.setCreatedAt(java.time.LocalDateTime.of(2026, 10, 28, 12, 0));
        when(kbMapper.selectList(any())).thenReturn(List.of(first, second));

        List<AdminKbAdminService.KbAdminView> views = service.listAllKbs();

        assertThat(views).hasSize(2);
        assertThat(views.get(0).name()).isEqualTo("演示库");
        assertThat(views.get(0).visibility()).isEqualTo("ALL");
        assertThat(views.get(1).id()).isEqualTo(12L);
        assertThat(views.get(1).createdAt()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 28, 12, 0));
    }

    @Test
    void listAllKbs_非系统管理员拒绝() {
        AuthContext.set(USER);
        assertThatThrownBy(() -> service.listAllKbs()).isInstanceOf(BusinessException.class);
        verify(kbMapper, never()).selectList(any());
    }
}
