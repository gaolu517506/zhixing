/**
 * 技术设施 · 文件存储：FileStorage 接口 → LocalFileStorage 本地磁盘实现（决策：学习规模不上 MinIO）。
 *
 * <p>安全红线：原始文件名来自客户端不可信，落盘前 FileNameUtil 清洗杜绝路径穿越；
 * 删除失败仅告警不回滚——磁盘不是事务资源。
 */
package com.zhixing.infra.storage;
