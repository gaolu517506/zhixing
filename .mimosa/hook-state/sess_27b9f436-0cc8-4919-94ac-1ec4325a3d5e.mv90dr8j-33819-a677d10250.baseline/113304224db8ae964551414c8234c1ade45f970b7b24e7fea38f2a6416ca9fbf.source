package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;

/**
 * 对话客户端。实现类是各供应商对同一 OpenAI 协议的适配器，
 * 协议骨架在 {@link AbstractOpenAIStyleChatClient}，供应商差异走钩子。
 */
public interface ChatClient {

    /** 供应商标识：deepseek / zhipu / ...，与 zhixing.llm.providers 的 key 对应。 */
    String provider();

    /** 同步调用：重写/摘要/标题等场景用。失败抛 {@link ModelClientException}。 */
    ChatResponse call(ChatRequest request);

    /**
     * 流式调用：阻塞直到流结束（谁调它谁给线程），增量经 callback 推给调用方。
     */
    void stream(ChatRequest request, StreamCallback callback);
}
