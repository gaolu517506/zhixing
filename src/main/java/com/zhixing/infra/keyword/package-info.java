/**
 * 技术设施 · 关键词检索通道：KeywordSearch 接口 → PgTrgmSearch（pg_trgm 字符三元组）。
 *
 * <p>D6 退出策略的落点：trgm 是带退出策略的过渡实现（召回弱于真 BM25 已诚实声明），
 * 升级路径 trgm 数组（已实现）→ zhparser → pg_search → ES，由评估集数据裁决、接口保证主链路零改动。
 */
package com.zhixing.infra.keyword;
