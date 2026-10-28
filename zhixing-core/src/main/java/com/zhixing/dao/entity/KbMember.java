package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库成员（V4，F2，D14）。复合主键 (kb_id, user_id)——本实体只用于 insert 与
 * wrapper 查询/删除，不适用 updateById（MyBatis-Plus 单主键语义）。
 */
@Data
@TableName("kb_member")
public class KbMember {

    @TableId(type = IdType.INPUT)
    private Long kbId;

    private Long userId;

    /** ADMIN / MEMBER（库角色两级） */
    private String role;

    private LocalDateTime createdAt;
}
