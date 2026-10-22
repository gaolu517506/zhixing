package com.zhixing.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixing.dao.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    /**
     * 挂起恢复转 RUNNING：显式 SQL 清空 pending_tool（updateById 默认策略跳过 null 字段，清不掉）。
     */
    @Update("UPDATE agent_run SET status = 'RUNNING', pending_tool = NULL, updated_at = now() WHERE id = #{id}")
    int resumeFromConfirm(@Param("id") Long id);
}
