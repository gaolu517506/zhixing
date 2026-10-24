package com.zhixing.infra.llm;

import okhttp3.OkHttpClient;

/**
 * 智谱（GLM 系列）对话客户端。
 * 智谱流式 chunk 自带 usage，且对 stream_options 的兼容性未经核实，
 * 故关闭 include_usage——这正是钩子的意义：协议骨架不动，供应商差异一行覆写。
 */
public class ZhipuChatClient extends AbstractOpenAIStyleChatClient {

    public ZhipuChatClient(OkHttpClient httpClient, String baseUrl, String apiKey, String chatModel) {
        super(httpClient, baseUrl, apiKey, chatModel);
    }

    @Override
    public String provider() {
        return "zhipu";
    }

    @Override
    protected boolean streamIncludeUsage() {
        return false;
    }
}
