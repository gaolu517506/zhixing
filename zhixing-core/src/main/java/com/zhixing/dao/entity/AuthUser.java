package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户（V4，F1）。ID 由服务层雪花赋值；password_hash 存 BCrypt；
 * status=DISABLED 时存量会话在下次请求即失效（校验链查状态）。
 */
@Data
@TableName("auth_user")
public class AuthUser {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String username;

    private String passwordHash;

    /** ACTIVE / DISABLED */
    private String status;

    /** ADMIN / USER */
    private String systemRole;

    private LocalDateTime createdAt;
}
