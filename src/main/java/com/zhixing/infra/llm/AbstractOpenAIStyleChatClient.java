package com.zhixing.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.zhixing.infra.llm.dto.ChatUsage;
import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.infra.llm.dto.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 协议风格对话客户端的模板方法基类（本日最核心的类）。
 *
 * <p>切分标准：不变的协议骨架（建 body、POST、状态码分类、SSE 循环）全部钉死在这里；
 * 只有"供应商差异"才允许下沉为钩子——当前就两个：是否支持 stream_options.include_usage、
 * 供应商特有 body 参数。子类应该只剩 provider() + 若干钩子覆写（约 20 行）。
 *
 * <p>流式收尾语义（容错三规则的落点）：
 * ① 单行解析失败由解析器降级为 EMPTY，不断流；
 * ② finish_reason 到达先记账不立即结束——usage 块在 finish 块之后、[DONE] 之前，
 *    等到 [DONE] 或 EOF 任一即收尾；EOF/读超时都视为流自然终止，
 *    若 finish_reason 已到则按正常收尾回调，绝不让完整答案被一个迟到的 [DONE] 毁掉；
 * ③ HTTP 200 但 body 带 error（含流中 error 事件）一律当失败。
 */
@Slf4j
public abstract class AbstractOpenAIStyleChatClient implements ChatClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final OpenAiStyleSseParser PARSER = new OpenAiStyleSseParser();

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int LOG_SNIPPET_LENGTH = 300;

    private final OkHttpClient httpClient;

    private final String baseUrl;

    private final String apiKey;

    private final String defaultModel;

    protected AbstractOpenAIStyleChatClient(OkHttpClient httpClient, String baseUrl, String apiKey, String defaultModel) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
    }

    // ==== 钩子：供应商差异只允许出现在这一段，协议骨架不许子类碰 ====

    /** 供应商是否支持 stream_options.include_usage（不支持或 usage 自带回的覆写为 false）。 */
    protected boolean streamIncludeUsage() {
        return true;
    }

    /** 供应商特有参数扩展点；默认不动 body。 */
    protected void customizeBody(ObjectNode body, ChatRequest request) {
    }

    @Override
    public ChatResponse call(ChatRequest request) {
        Request httpRequest = buildRequest(request, false);
        String body;
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            ResponseBody responseBody = response.body();
            body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw httpError(response.code(), body);
            }
        } catch (ModelClientException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new ModelClientException(ErrorType.TIMEOUT, provider() + " 同步调用读超时", e);
        } catch (IOException e) {
            throw new ModelClientException(ErrorType.NETWORK_ERROR, provider() + " 同步调用网络异常：" + e.getMessage(), e);
        }
        return parseChatBody(body);
    }

    @Override
    public void stream(ChatRequest request, StreamCallback callback) {
        Request httpRequest = buildRequest(request, true);
        ChatUsage usage = null;
        String finishReason = null;
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                throw httpError(response.code(), responseBody != null ? responseBody.string() : "");
            }
            if (responseBody == null) {
                throw new ModelClientException(ErrorType.INVALID_RESPONSE, provider() + " 流式响应无 body");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8));
            String line;
            ToolCallAggregator aggregator = new ToolCallAggregator();
            while ((line = reader.readLine()) != null) {
                ParsedEvent event = PARSER.parseLine(line);
                // 容错规则 3（流式版）：流中 error 事件必须当失败，立即终止并回调 onError
                if (event.errorMessage() != null) {
                    log.warn("[{}] 流中收到 error 事件：{}", provider(), event.errorMessage());
                    callback.onError(new ModelClientException(ErrorType.SERVER_ERROR, event.errorMessage()));
                    return;
                }
                if (event.done()) {
                    break;
                }
                if (event.content() != null) {
                    callback.onContent(event.content());
                }
                if (event.toolCallDeltas() != null) {
                    event.toolCallDeltas().forEach(aggregator::feed);
                }
                if (event.reasoning() != null) {
                    callback.onThinking(event.reasoning());
                }
                if (event.finishReason() != null) {
                    finishReason = event.finishReason();
                }
                if (event.usage() != null) {
                    usage = event.usage();
                }
            }
            if (finishReason == null) {
                // EOF 了既无 finish_reason 也无 [DONE]：流被截断，必须当失败而不是把半截答案当成功
                callback.onError(new ModelClientException(ErrorType.INVALID_RESPONSE,
                        provider() + " 流提前结束：未收到 finish_reason/[DONE]"));
            } else {
                // 阶段 3 契约：finishReason=tool_calls 时先回调聚合结果，再 onComplete 收尾
                if ("tool_calls".equals(finishReason)) {
                    callback.onToolCalls(aggregator.snapshot());
                }
                callback.onComplete(finishReason, usage);
            }
        } catch (ModelClientException e) {
            callback.onError(e);
        } catch (SocketTimeoutException e) {
            // finish_reason 已到而 [DONE] 迟迟不来：答案已完整，读超时视同正常收尾（容错规则 2）
            if (finishReason != null) {
                callback.onComplete(finishReason, usage);
            } else {
                callback.onError(new ModelClientException(ErrorType.TIMEOUT, provider() + " 流式读取超时", e));
            }
        } catch (IOException e) {
            if (finishReason != null) {
                callback.onComplete(finishReason, usage);
            } else {
                callback.onError(new ModelClientException(ErrorType.NETWORK_ERROR,
                        provider() + " 流式网络异常：" + e.getMessage(), e));
            }
        } catch (RuntimeException e) {
            callback.onError(e);
        }
    }

    private Request buildRequest(ChatRequest request, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.getModel() != null ? request.getModel() : defaultModel);
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage message : request.getMessages()) {
            var node = messages.addObject();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            // 阶段 3：assistant 携带 tool_calls（发起的工具调用）
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                var calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    var callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    callNode.putObject("function")
                            .put("name", call.name())
                            .put("arguments", call.arguments() != null ? call.arguments() : "{}");
                }
            }
            // 阶段 3：tool 结果消息（与调用 id 配对）
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
        }
        body.put("stream", stream);
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        // 阶段 3：工具声明（Function Calling）
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            var tools = body.putArray("tools");
            for (ToolDefinition tool : request.getTools()) {
                var toolNode = tools.addObject();
                toolNode.put("type", "function");
                var function = toolNode.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                try {
                    function.set("parameters", MAPPER.readTree(tool.parametersSchema()));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("工具 " + tool.name() + " 的 parametersSchema 不是合法 JSON", e);
                }
            }
        }
        if (stream && streamIncludeUsage()) {
            body.putObject("stream_options").put("include_usage", true);
        }
        customizeBody(body, request);
        return new Request.Builder()
                .url(baseUrl + CHAT_COMPLETIONS_PATH)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }

    private ChatResponse parseChatBody(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new ModelClientException(ErrorType.INVALID_RESPONSE, provider() + " 返回的不是合法 JSON", e);
        }
        // 容错规则 3：HTTP 200 但 body 携带 error —— 必须当失败
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            String message = error.isObject()
                    ? error.path("message").asText(error.toString())
                    : error.asText();
            log.warn("[{}] HTTP 200 但返回 error：{}", provider(), message);
            throw new ModelClientException(ErrorType.SERVER_ERROR, provider() + " 返回业务错误：" + message);
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode() || !choice.isObject()) {
            throw new ModelClientException(ErrorType.INVALID_RESPONSE,
                    provider() + " 响应缺少 choices，body 片段：" + snippet(body));
        }
        String content = choice.path("message").path("content").asText(null);
        String finishReason = choice.path("finish_reason").asText(null);
        return new ChatResponse(content, finishReason, ChatUsage.fromJson(root.get("usage")));
    }

    /** 非 2xx 统一走这里：状态码分类，供应商原始 body 只进日志，对外 message 保持干净。 */
    private ModelClientException httpError(int status, String body) {
        ErrorType type = ErrorType.fromHttpStatus(status);
        log.warn("[{}] HTTP {} {}，body：{}", provider(), status, type, snippet(body));
        return ModelClientException.fromHttpStatus(status,
                provider() + " HTTP " + status + "（" + type.getLabel() + "）");
    }

    private static String snippet(String body) {
        String flat = body == null ? "" : body.replaceAll("\\s+", " ");
        return flat.length() <= LOG_SNIPPET_LENGTH ? flat : flat.substring(0, LOG_SNIPPET_LENGTH) + "...";
    }
}
