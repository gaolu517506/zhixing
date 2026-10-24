/**
 * 数据访问层：MyBatis-Plus entity/mapper + 全项目唯一允许手写 SQL 的地方。
 *
 * <p>内容：entity/（七表实体，雪花 ID）、mapper/（BaseMapper + 注解 SQL——
 * 向量 {@code ?::vector} cast、trgm 数组匹配、JSONB TypeHandler 均在此层）。
 *
 * <p>依赖规则：只被上层依赖，自身不依赖任何业务包；
 * 表结构以 V1__init.sql 为只读契约，跨线共享表（如 kb_* 查询与入库分家）的边界见各任务书。
 */
package com.zhixing.dao;
