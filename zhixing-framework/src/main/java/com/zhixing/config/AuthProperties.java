package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.auth 配置段绑定（认证线 T6-0-A）。默认值即 02-技术方案 §B1 的规格钉死值，
 * 仅测试与特殊部署需要时覆盖。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.auth")
public class AuthProperties {

    /** 会话有效期（天）：7 天滑动续期（§B1）。 */
    private int sessionTtlDays = 7;

    /** 滑动续期节流窗口（小时）：剩余寿命低于该值才续期——活跃会话至多每天一次 UPDATE，
     *  避免每个请求都写库；语义仍是「活跃即不过期」。 */
    private int renewWindowHours = 24;

    /** 连续失败锁定阈值（§B1：连续失败 5 次锁 5 分钟，单实例内存计数）。 */
    private int maxFailedAttempts = 5;

    /** 锁定时长（分钟）。 */
    private int lockMinutes = 5;
}
