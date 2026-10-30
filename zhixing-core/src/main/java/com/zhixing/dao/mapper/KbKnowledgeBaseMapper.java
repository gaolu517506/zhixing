package com.zhixing.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixing.dao.entity.KbKnowledgeBase;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface KbKnowledgeBaseMapper extends BaseMapper<KbKnowledgeBase> {

    /**
     * 当前用户可见库全集（02-技术方案 §B2 + §B9：visibility=ALL ∪ 直属成员 ∪ 我所属组织的库，
     * 权限收敛单点 SQL；org_id 为空的组织语义不适用）。参数绑定：#{userId} 预编译占位，无拼接。
     */
    @Select("SELECT id FROM kb_knowledge_base WHERE visibility = 'ALL' "
            + "UNION SELECT kb_id FROM kb_member WHERE user_id = #{userId} "
            + "UNION SELECT kb.id FROM kb_knowledge_base kb "
            + "JOIN org_member om ON om.org_id = kb.org_id WHERE om.user_id = #{userId}")
    List<Long> selectAccessibleIds(@Param("userId") Long userId);
}
