package com.zhixing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.KbMember;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.KbMemberMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库权限判定单点实现（02-技术方案 §B2，D14）。全项目唯一的权限语义：
 * controller 层零权限判断，用例在 service 入口调用本接口；无权限统一抛
 * {@link BusinessException}({@link ErrorCode#FORBIDDEN})，由全局异常处理器翻译为 A0006。
 *
 * <p>可见性语义（V4）：PRIVATE 仅库成员与系统 ADMIN；ALL 对任何登录用户开放。
 * 判定读 {@link AuthContext}（请求级身份由 AuthFilter 注入，agent-pool 异步段由
 * AgentService.launch 传播，见该类注释）。
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    private static final String VISIBILITY_ALL = "ALL";

    private static final String ROLE_KB_ADMIN = "ADMIN";

    private final KbKnowledgeBaseMapper kbMapper;

    private final KbMemberMapper memberMapper;

    public PermissionServiceImpl(KbKnowledgeBaseMapper kbMapper, KbMemberMapper memberMapper) {
        this.kbMapper = kbMapper;
        this.memberMapper = memberMapper;
    }

    @Override
    public void requireAccess(Long kbId) {
        KbKnowledgeBase kb = requireKb(kbId);
        CurrentUser user = AuthContext.current();
        if (VISIBILITY_ALL.equals(kb.getVisibility()) || user.isAdmin() || isMember(kbId, user.id())) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权限访问该知识库");
    }

    @Override
    public void requireKbAdmin(Long kbId) {
        requireKb(kbId);
        CurrentUser user = AuthContext.current();
        if (user.isAdmin() || isKbAdmin(kbId, user.id())) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "仅库管理员可执行该操作");
    }

    @Override
    public List<Long> accessibleKbIds() {
        // 02 §B2 字面口径：ALL ∪ 直属成员（系统 ADMIN 不例外，管理后台全量视图走 B7 专属端点）
        return kbMapper.selectAccessibleIds(AuthContext.current().id());
    }

    private KbKnowledgeBase requireKb(Long kbId) {
        KbKnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ErrorCode.KB_NOT_FOUND);
        }
        return kb;
    }

    private boolean isMember(Long kbId, Long userId) {
        return memberMapper.selectCount(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, userId)) > 0;
    }

    private boolean isKbAdmin(Long kbId, Long userId) {
        return memberMapper.selectCount(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, userId)
                .eq(KbMember::getRole, ROLE_KB_ADMIN)) > 0;
    }
}
