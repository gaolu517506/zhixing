package com.zhixing.infra.llm;

import okhttp3.OkHttpClient;

/**
 * DeepSeek 对话客户端。协议骨架全在基类，这里只剩身份与模型默认值——模板方法的回报。
 */
public class DeepSeekChatClient extends AbstractOpenAIStyleChatClient {

    public DeepSeekChatClient(OkHttpClient httpClient, String baseUrl, String apiKey, String chatModel) {
        super(httpClient, baseUrl, apiKey, chatModel);
    }

    @Override
    public String provider() {
        return "deepseek";
    }
}
