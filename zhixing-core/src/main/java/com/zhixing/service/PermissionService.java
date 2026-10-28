package com.zhixing.service;

import java.util.List;

/**
 * 知识库权限判定单点（T6-0 契约基线，D14）。
 *
 * <p>实现要求：全项目唯一的权限语义——controller 层零权限判断，所有用例在
 * service 入口调用本接口；SSE 与文件下载因检查在 service 入口而天然同权。
 * 无权限抛 {@code BusinessException(ErrorCode.FORBIDDEN)}，由全局异常处理器
 * 翻译为 A0006。
 */
public interface PermissionService {

    /**
     * 要求当前登录用户可访问指定库：visibility=ALL，或为库成员，或系统 ADMIN。
     */
    void requireAccess(Long kbId);

    /**
     * 要求当前登录用户是指定库的管理者：库 ADMIN 或系统 ADMIN（库设置/成员管理/评测发起等）。
     */
    void requireKbAdmin(Long kbId);

    /**
     * 当前登录用户可见的库 ID 全集（visibility=ALL ∪ 直属成员），供列表收敛单点查询。
     */
    List<Long> accessibleKbIds();
}
