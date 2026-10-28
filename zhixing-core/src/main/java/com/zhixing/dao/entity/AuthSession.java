package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务端会话（V4，F1，D13）。主键即 token 的 SHA-256——明文只在登录响应出现一次；
 * 登出删除本行即吊销。
 */
@Data
@TableName("auth_session")
public class AuthSession {

    /** 明文 token 的 SHA-256 十六进制（64 字符） */
    @TableId(type = IdType.INPUT)
    private String tokenHash;

    private Long userId;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
