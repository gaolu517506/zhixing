package com.zhixing.service;

import com.zhixing.common.AuthTokenException;
import com.zhixing.common.auth.CurrentUser;

/**
 * 开放 API token 解析策略（T6-0 契约基线，D16）。
 *
 * <p>调用方：bootstrap 的 AuthFilter——会话未命中后按策略调用本接口，
 * 命中则以 owner 身份 + API 渠道继续（与前端同一权限模型）。
 * 实现归属：v2.0 线 B（api_token 表）；线 A 的 Filter 只依赖本契约。
 */
public interface TokenResolver {

    /**
     * 解析明文 token。
     *
     * @param rawToken 请求 Authorization 头中的明文（zxk_ 前缀）
     * @return 命中且 ACTIVE、未过期的主体（channel=API，principal=owner）；未命中/已吊销/已过期返回 null（由 Filter 决定 401）
     * @throws AuthTokenException 命中但日配额已用尽（A0007）——命中与超额是两种语义，Filter 分别翻译
     */
    CurrentUser resolve(String rawToken) throws AuthTokenException;
}
