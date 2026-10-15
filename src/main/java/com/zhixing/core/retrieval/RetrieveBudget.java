package com.zhixing.core.retrieval;

/**
 * 单次检索的通道预算（详设 §4.3：20 / 20 / 5）。
 */
public record RetrieveBudget(int vectorTopK, int keywordTopK, int rerankTopK) {

    public static RetrieveBudget of(int vectorTopK, int keywordTopK, int rerankTopK) {
        return new RetrieveBudget(vectorTopK, keywordTopK, rerankTopK);
    }
}
