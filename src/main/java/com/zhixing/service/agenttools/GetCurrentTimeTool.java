package com.zhixing.service.agenttools;

import com.zhixing.core.agent.Tool;
import com.zhixing.core.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * get_current_time（读，详设 13-阶段3 §6 最简演示工具）：返回服务本地日期时间与时区，
 * 让模型学会调用无参工具；也供需要"当前时间"做判断的问题（如"保修期到没到"）取证。
 */
@Component
public class GetCurrentTimeTool implements Tool {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "获取当前的本地日期、时间与时区。当用户询问现在几点、今天日期，"
                + "或问题需要以当前时间推算（如保修是否过期、活动是否截止）时调用；无需任何参数。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        ZoneId zone = ZoneId.systemDefault();
        String now = LocalDateTime.now(zone).format(FORMAT);
        return ToolResult.ok("当前时间：" + now + "（时区 " + zone.getId() + "）");
    }
}
