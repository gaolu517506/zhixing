package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织（02-技术方案 §B9，F10 多租户）。部门形态：组织 × 知识库 × 成员三级隔离；
 * ID 由服务层雪花赋值（IdType.INPUT，沿 kb_knowledge_base 同约定）。
 */
@Data
@TableName("organization")
public class Organization {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String name;

    private LocalDateTime createdAt;
}
