package com.zhixing.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixing.dao.entity.ApiToken;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

public interface ApiTokenMapper extends BaseMapper<ApiToken> {

    /**
     * 原子消费一次当日配额（读-判-写在并发下会超卖）：used_date 非当天自动滚动归 1
     * （日配额跨天重置的落点），当天则 +1。WHERE 的状态与余量守卫让消耗与判定
     * 同一行锁内完成，0 行 = 超额或已吊销。全 #{} 绑定。
     */
    @Update("UPDATE api_token SET used_today = CASE WHEN used_date = #{today} THEN used_today + 1 ELSE 1 END, "
            + "used_date = #{today}, last_used_at = now() "
            + "WHERE id = #{id} AND status = 'ACTIVE' AND (used_date < #{today} OR used_today < daily_quota)")
    int consumeQuota(@Param("id") long id, @Param("today") LocalDate today);
}
