/**
 * 技术设施 · 模型客户端：自研 OpenAI 兼容协议栈（决策 D3，不用 Spring AI/LangChain4j）。
 *
 * <p>内容：ChatClient/EmbeddingClient/RerankClient 接口、AbstractOpenAIStyleChatClient
 * 模板方法基类（协议骨架 + 容错三规则）、DeepSeek/Zhipu 实现、OpenAiStyleSseParser（纯函数）、
 * ModelClientException + ErrorType（fallbackCapable 是阶段容错降级的直接输入，DL-8）、dto/。
 *
 * <p>依赖规则：不依赖 service/controller/core；消费方（core/service）只面向接口。
 * 包结构治理注：LlmExceptionHandler 已归位 controller（web 组件不落 infra）。
 */
package com.zhixing.infra.llm;
