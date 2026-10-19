package com.zhixing.core.memory;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重写的直通兜底实现（契约基线默认装配）：永远返回 rewritten=false，调用方以原问题直查。
 * T2-B 线的 LLM 实现以 @Primary 覆盖本 bean；rewrite.enabled=false 时 QaService 根本不调用重写。
 */
@Component
public class NoopQueryRewriter implements QueryRewriter {

    @Override
    public RewrittenQuery rewrite(List<Turn> history, String summary, String question) {
        return new RewrittenQuery(question, List.of(), false);
    }
}
