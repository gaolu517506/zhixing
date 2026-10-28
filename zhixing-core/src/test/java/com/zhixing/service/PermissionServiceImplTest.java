package com.zhixing.service;

import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.KbMemberMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限判定单点单测（02 §B2）：visibility=ALL 直通、成员/库管理员/系统管理员语义、
 * 无库 K1001、无权限 A0006、accessibleKbIds 单点收敛。
 */
class PermissionServiceImplTest {

    private KbKnowledgeBaseMapper kbMapper;

    private KbMemberMapper memberMapper;

    private PermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        kbMapper = mock(KbKnowledgeBaseMapper.class);
        memberMapper = mock(KbMemberMapper.class);
        service = new PermissionServiceImpl(kbMapper, memberMapper);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private void loginAs(String systemRole) {
        AuthContext.set(new CurrentUser(1L, "tester", systemRole, AuthChannel.WEB));
    }

    private KbKnowledgeBase kb(String visibility) {
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(9L);
        kb.setVisibility(visibility);
        return kb;
    }

    @Test
    void requireAccess_库不存在K1001() {
        loginAs("USER");
        when(kbMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.requireAccess(404L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.KB_NOT_FOUND));
    }

    @Test
    void requireAccess_ALL库任何登录用户可访问() {
        loginAs("USER");
        when(kbMapper.selectById(9L)).thenReturn(kb("ALL"));
        assertThatCode(() -> service.requireAccess(9L)).doesNotThrowAnyException();
        // ALL 短路：不查成员表
        org.mockito.Mockito.verifyNoInteractions(memberMapper);
    }

    @Test
    void requireAccess_成员可访问() {
        loginAs("USER");
        when(kbMapper.selectById(9L)).thenReturn(kb("PRIVATE"));
        when(memberMapper.selectCount(any())).thenReturn(1L);
        assertThatCode(() -> service.requireAccess(9L)).doesNotThrowAnyException();
    }

    @Test
    void requireAccess_系统管理员绕过成员判定() {
        loginAs("ADMIN");
        when(kbMapper.selectById(9L)).thenReturn(kb("PRIVATE"));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        assertThatCode(() -> service.requireAccess(9L)).doesNotThrowAnyException();
    }

    @Test
    void requireAccess_非成员A0006() {
        loginAs("USER");
        when(kbMapper.selectById(9L)).thenReturn(kb("PRIVATE"));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> service.requireAccess(9L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void requireKbAdmin_库成员但角色MEMBER被拒() {
        loginAs("USER");
        when(kbMapper.selectById(9L)).thenReturn(kb("PRIVATE"));
        // selectCount 同一 stub：MEMBER 角色查询也返回 1——但 requireKbAdmin 查 role=ADMIN，须精确：
        // 用连续返回：第一次（ADMIN 角色查询）= 0
        when(memberMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> service.requireKbAdmin(9L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void requireKbAdmin_库管理员与系统管理员通过() {
        when(kbMapper.selectById(9L)).thenReturn(kb("PRIVATE"));

        loginAs("USER");
        when(memberMapper.selectCount(any())).thenReturn(1L);
        assertThatCode(() -> service.requireKbAdmin(9L)).doesNotThrowAnyException();

        loginAs("ADMIN");
        when(memberMapper.selectCount(any())).thenReturn(0L);
        assertThatCode(() -> service.requireKbAdmin(9L)).doesNotThrowAnyException();
    }

    @Test
    void accessibleKbIds_收敛单点查询() {
        loginAs("USER");
        when(kbMapper.selectAccessibleIds(1L)).thenReturn(List.of(9L, 10L));
        assertThat(service.accessibleKbIds()).containsExactly(9L, 10L);
    }
}
