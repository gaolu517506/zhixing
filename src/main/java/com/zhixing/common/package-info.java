/**
 * 统一响应协议与异常体系：全项目唯一的对外协议层。
 *
 * <p>内容：Result 协议、ErrorCode 三段式错误码（A 客户端/B 服务端/C 远程）、
 * BusinessException、GlobalExceptionHandler（web 异常翻译入口）。
 *
 * <p>依赖规则（全项目最严格）：本包不依赖任何其他业务包——
 * 任何一层都可以依赖 common，common 不依赖任何一层。
 */
package com.zhixing.common;
