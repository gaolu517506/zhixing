package com.zhixing.controller;

import com.zhixing.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("zhixing up");
    }

    /** echo：练习参数校验失败 → A0000 路径（D4 验收第 4 条）；times 顺带覆盖参数类型不匹配分支。 */
    @GetMapping("/dev/echo")
    public Result<String> echo(@Valid @ModelAttribute EchoRequest request,
                               @RequestParam(required = false) Integer times) {
        return Result.ok(times == null ? request.msg() : request.msg() + " x" + times);
    }

    public record EchoRequest(
            @NotBlank(message = "msg 不能为空")
            @Size(max = 10, message = "msg 长度不能超过 10")
            String msg) {
    }
}
