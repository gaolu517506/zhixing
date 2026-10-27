package com.zhixing.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixing.dao.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    /**
     * 挂起恢复转 RUNNING（CAS，P0）：仅当仍处 WAITING_CONFIRM 时生效，返回影响行数——
     * 并发双 confirm 只有首个能把状态翻走，写工具的单次执行保证由本守卫承担。
     * 显式 SQL 清空 pending_tool（updateById 默认策略跳过 null 字段，清不掉）。
     */
    @Update("UPDATE agent_run SET status = 'RUNNING', pending_tool = NULL, updated_at = now() "
            + "WHERE id = #{id} AND status = 'WAITING_CONFIRM'")
    int resumeFromConfirm(@Param("id") Long id);
}
