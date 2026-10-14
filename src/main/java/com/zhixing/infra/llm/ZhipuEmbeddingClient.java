package com.zhixing.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 智谱 embedding 客户端（embedding-3，dimensions 入参 256~2048）。
 * 批量超过 batch-size 自动分片，结果用 List.set 按原始顺序回填；
 * HTTP 200 但 body 带 error 抛异常（容错规则 3 的实体化）。
 */
@Slf4j
public class ZhipuEmbeddingClient implements EmbeddingClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    private final String baseUrl;

    private final String apiKey;

    private final String model;

    private final int dimensions;

    private final int batchSize;

    public ZhipuEmbeddingClient(OkHttpClient httpClient, String baseUrl, String apiKey,
                                String model, int dimensions, int batchSize) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
    }

    @Override
    public String provider() {
        return "zhipu";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(Collections.nCopies(texts.size(), null));
        int offset = 0;
        for (List<String> shard : partition(texts, batchSize)) {
            embedShard(shard, offset, result);
            offset += shard.size();
        }
        return result;
    }

    /** 按批大小切分，保持输入顺序；纯函数便于离线单测。 */
    static List<List<String>> partition(List<String> texts, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须 > 0，实际：" + batchSize);
        }
        List<List<String>> shards = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            shards.add(texts.subList(i, Math.min(i + batchSize, texts.size())));
        }
        return shards;
    }

    /** 调一批，并把返回向量按 API 给的 index 回填进 result 的 [offset, offset+shard.size()) 区间。 */
    private void embedShard(List<String> shard, int offset, List<float[]> result) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        shard.forEach(input::add);
        body.put("dimensions", dimensions);

        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        String responseBody;
        try {
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody rb = response.body();
                responseBody = rb != null ? rb.string() : "";
                if (!response.isSuccessful()) {
                    throw ModelClientException.fromHttpStatus(response.code(),
                            provider() + " HTTP " + response.code() + "（embedding 调用失败）");
                }
            }
        } catch (ModelClientException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new ModelClientException(ErrorType.TIMEOUT, provider() + " embedding 调用读超时", e);
        } catch (IOException e) {
            throw new ModelClientException(ErrorType.NETWORK_ERROR, provider() + " embedding 网络异常：" + e.getMessage(), e);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new ModelClientException(ErrorType.INVALID_RESPONSE, provider() + " embedding 返回不是合法 JSON", e);
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            String message = error.isObject() ? error.path("message").asText(error.toString()) : error.asText();
            throw new ModelClientException(ErrorType.SERVER_ERROR, provider() + " embedding 返回业务错误：" + message);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new ModelClientException(ErrorType.INVALID_RESPONSE, provider() + " embedding 响应缺少 data 数组");
        }
        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.get(i);
            int index = item.has("index") && item.get("index").isInt() ? item.get("index").asInt() : i;
            result.set(offset + index, toVector(item.path("embedding")));
        }
    }

    private float[] toVector(JsonNode embedding) {
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new ModelClientException(ErrorType.INVALID_RESPONSE, provider() + " embedding 响应缺少向量数组");
        }
        if (embedding.size() != dimensions) {
            // 维度错就早炸：带病向量入库后才是真灾难（整列作废重建）
            throw new ModelClientException(ErrorType.INVALID_RESPONSE,
                    provider() + " 向量维度不符：期望 " + dimensions + "，实际 " + embedding.size());
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }
}
