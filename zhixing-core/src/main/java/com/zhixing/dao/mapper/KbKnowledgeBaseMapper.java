package com.zhixing.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixing.dao.entity.KbKnowledgeBase;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface KbKnowledgeBaseMapper extends BaseMapper<KbKnowledgeBase> {

    /**
     * 当前用户可见库全集（02-技术方案 §B2：visibility=ALL ∪ 直属成员，权限收敛单点 SQL）。
     * 参数绑定：#{userId} 预编译占位，无拼接。
     */
    @Select("SELECT id FROM kb_knowledge_base WHERE visibility = 'ALL' "
            + "UNION SELECT kb_id FROM kb_member WHERE user_id = #{userId}")
    List<Long> selectAccessibleIds(@Param("userId") Long userId);
}
