package com.zhixing.core.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.core.memory.Turn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估集解析与分层聚合（详设 §10.1）。与 runner 的执行解耦——解析/聚合是纯逻辑可离线单测，
 * EvalRunner 只负责"调旁路 + 汇总打印"这一层。
 */
public final class EvalSetSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalSetSupport() {
    }

    /**
     * 评估集一行：expected_doc_ids 是业务码（doc 级标注稳定，不随分块策略失效）；jsonl 为 snake_case。
     * history 非空 = 多轮用例（T2-6）：runner 走真实重写路径（standalone_question + keywords 进检索）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvalCase(String id, String query,
                           @JsonProperty("expected_doc_ids") List<String> expectedDocIds,
                           String difficulty, String note,
                           @JsonProperty("history") List<Turn> history) {
    }

    /** 单题计算结果（docId 维度指标由调用方解析业务码后填入）。 */
    public record CaseResult(EvalCase evalCase, List<Long> rankedDocIds, double hitAt5, double recallAt5, double mrrAt10) {
    }

    public static List<EvalCase> parse(Path jsonl) {
        try {
            List<String> lines = Files.readAllLines(resolve(jsonl));
            List<EvalCase> cases = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                cases.add(MAPPER.readValue(line, EvalCase.class));
            }
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("评估集读取失败: " + jsonl, e);
        }
    }

    /**
     * 相对路径锚定：多模块下 surefire 工作目录是模块目录（zhixing-core/），仓库根相对路径
     * 直接读会断（与 MCP 测试资产同族问题）；从当前目录逐级向上找第一个可读位置，找不到
     * 则原样返回（让 parse 报出携带原路径的错误）。
     */
    static Path resolve(Path configured) {
        if (configured.isAbsolute() || Files.isReadable(configured)) {
            return configured;
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(configured);
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return configured;
    }

    /** 按 easy/medium/hard 分层（详设 §10.3：hard 掉了先查分块，easy 掉了先查通道）。 */
    public static Map<String, List<CaseResult>> groupByDifficulty(List<CaseResult> results) {
        Map<String, List<CaseResult>> grouped = new LinkedHashMap<>();
        for (Difficulty d : Difficulty.values()) {
            grouped.put(d.label(), new ArrayList<>());
        }
        for (CaseResult r : results) {
            String difficulty = r.evalCase().difficulty() == null ? "medium" : r.evalCase().difficulty().toLowerCase();
            grouped.computeIfAbsent(difficulty, key -> new ArrayList<>()).add(r);
        }
        return grouped;
    }

    public static double averageHitAt5(List<CaseResult> results) {
        return results.stream().mapToDouble(CaseResult::hitAt5).average().orElse(0.0);
    }

    private enum Difficulty {
        EASY("easy"), MEDIUM("medium"), HARD("hard");

        private final String label;

        Difficulty(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
