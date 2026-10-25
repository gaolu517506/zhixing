package com.zhixing.infra.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * embedding 备胎容错装饰器（v1.1-B T5-2，DL-19：请求级切换 + 维度守卫）。
 * 包装 primary + optional fallback，对上层（IngestionPipeline/RetrievalEngine）完全透明。
 *
 * <p><b>切换语义（请求级，每次调用独立决策）</b>：primary 失败且属于可切换错误
 * （网络异常 / 超时 / HTTP 5xx / 429）→ 切 fallback 重试一次；fallback 缺失或也失败 →
 * 抛 {@link EmbeddingUnavailableException}（继承 ModelClientException，对外映射 C0001），
 * message 含主备双方失败摘要。401/400 等语义错误（key 无效、参数错）切了也没用，
 * 原样上抛不切换——与 chat 侧 ErrorType.fallbackCapable 思想一致。
 *
 * <p><b>与 chat 侧的一处有意差异</b>：embedding 备胎存在的第一动因就是智谱 429
 * （余额/限流，2026-10-22 旧账），故 {@link ErrorType#RATE_LIMITED} 在此可切换；
 * 共享枚举的 chat 语义（429 不切换）冻结不动，差异收敛在本类的 {@link #switchable}。
 *
 * <p><b>维度守卫（不变式）</b>：任何候选返回的向量维度 ≠ 配置维度 → 拒绝返回并抛
 * INVALID_RESPONSE（C 段，防脏向量落库——整列作废重建的代价远高于一次调用失败）。
 * 守卫是结果校验器而非失败分类器：守卫拒绝属确定性配置/模型错误，不触发切换
 * （与 chat 侧 INVALID_RESPONSE 不可降级同理）。
 *
 * <p><b>无备胎时行为与现状一致</b>：结果原样透传、异常原样上抛（同一实例），
 * 仅多一道维度防线（真实客户端自身已校验，该防线对正确实现零触发）。
 * 装饰器无状态、线程安全。
 */
@Slf4j
public class ResilientEmbeddingDecorator implements EmbeddingClient {

    private final EmbeddingClient primary;

    private final EmbeddingClient fallback;

    /** 装配期锁定的维度不变式（= zhixing.llm.embedding.dimensions = DDL vector(N)）。 */
    private final int expectedDimensions;

    public ResilientEmbeddingDecorator(EmbeddingClient primary, int expectedDimensions) {
        this(primary, null, expectedDimensions);
    }

    public ResilientEmbeddingDecorator(EmbeddingClient primary, EmbeddingClient fallback, int expectedDimensions) {
        if (primary == null) {
            throw new IllegalArgumentException("primary embedding 客户端不能为空");
        }
        this.primary = primary;
        this.fallback = fallback;
        this.expectedDimensions = expectedDimensions;
    }

    @Override
    public String provider() {
        return primary.provider();
    }

    @Override
    public int dimensions() {
        return primary.dimensions();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        try {
            return checked(primary.embedAll(texts));
        } catch (ModelClientException e) {
            if (fallback == null || !switchable(e)) {
                throw e; // 无备胎 / 语义错误（401/400 等）：与现状一致，原样上抛
            }
            log.warn("[embedding] primary({}) 失败，切换备胎({})：{}",
                    primary.provider(), fallback.provider(), e.getMessage());
            try {
                return checked(fallback.embedAll(texts));
            } catch (ModelClientException fe) {
                throw new EmbeddingUnavailableException(describeBoth(e, fe), e, fe);
            }
        }
    }

    /** 维度守卫：主备任何返回都必须等于配置维度，否则拒绝返回（防脏向量落库）。 */
    private List<float[]> checked(List<float[]> vectors) {
        if (vectors != null) {
            for (float[] vector : vectors) {
                if (vector == null || vector.length != expectedDimensions) {
                    throw new ModelClientException(ErrorType.INVALID_RESPONSE, "向量维度守卫拒绝：期望 "
                            + expectedDimensions + " 维，实际 " + (vector == null ? "缺失" : vector.length)
                            + " 维（防脏向量落库，DDL vector(" + expectedDimensions + ") 不变式）");
                }
            }
        }
        return vectors;
    }

    /**
     * embedding 侧可切换判定：网络 / 超时 / 5xx / 429。
     * 语义错误（UNAUTHORIZED/CLIENT_ERROR/INVALID_RESPONSE）与 chat 侧同样不切换。
     */
    private static boolean switchable(ModelClientException e) {
        return switch (e.getType()) {
            case NETWORK_ERROR, TIMEOUT, SERVER_ERROR, RATE_LIMITED -> true;
            default -> false;
        };
    }

    private String describeBoth(ModelClientException primaryFailure, ModelClientException fallbackFailure) {
        return "embedding 主备均不可用：primary[" + primary.provider() + "] " + primaryFailure.getMessage()
                + "；fallback[" + (fallback == null ? "未配置" : fallback.provider()) + "] " + fallbackFailure.getMessage();
    }
}
