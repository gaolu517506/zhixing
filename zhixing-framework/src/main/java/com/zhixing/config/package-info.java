/**
 * 配置绑定与基础设施装配：@ConfigurationProperties 属性类 + OkHttp/线程池/Jackson/CORS 的 @Configuration。
 *
 * <p>装配纪律：客户端/线程池 bean 显式注册而非组件扫描——它们是「配置的函数」，
 * 构造参数全部来自 yaml；属性注册集中在 RetrievalPoolConfig 等装配类的
 * {@code @EnableConfigurationProperties}，新增 Properties 类必须挂到对应装配类。
 *
 * <p>依赖规则：可依赖 infra 接口与 common；不依赖 service/controller 业务逻辑。
 */
package com.zhixing.config;
