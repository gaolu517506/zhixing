package com.zhixing.core.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评估集相对路径锚定（v1.1 T5-5）：多模块 surefire 工作目录 = 模块目录，
 * 仓库根相对路径必须能向上解析（拆分后 README 评测命令保持可用的回归锁）。
 */
class EvalSetSupportResolveTest {

    @Test
    void 仓库根相对路径可向上解析() {
        Path resolved = EvalSetSupport.resolve(Path.of("pom.xml"));
        // 仅存在于仓库根的受控文件（模块目录无副本），验证向上解析能力；
        // 不用 docs/ 下任何路径——docs 不入库，CI 环境不存在（T5-5 首跑红过的教训）
        Path rootOnly = EvalSetSupport.resolve(Path.of("LICENSE"));
        assertThat(Files.isReadable(resolved)).isTrue();
        assertThat(Files.isReadable(rootOnly)).isTrue();
    }

    @Test
    void 不存在的路径原样返回由上层报错() {
        Path missing = Path.of("no/such/eval-set.jsonl");
        assertThat(EvalSetSupport.resolve(missing)).isEqualTo(missing);
    }
}
