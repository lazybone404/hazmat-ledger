package com.lazybone.hazmat.msds.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 调百炼的 OpenAI 兼容 embedding 接口。
 *
 * 两处必须注意：单次请求条数有上限（默认按 10 分批），以及必须设超时——
 * 向量算不出来只是少一路召回，不该把后台线程一直挂住。
 */
@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    /** 接口单次请求的条数上限，配置超过它也按这个数发 */
    private static final int MAX_BATCH = 10;

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public HttpEmbeddingClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        int batchSize = Math.min(MAX_BATCH, Math.max(1, properties.getBatchSize()));
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            result.addAll(embedBatch(texts.subList(start, end)));
        }
        return result;
    }

    private List<float[]> embedBatch(List<String> batch) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getEmbeddingModel());
        body.put("encoding_format", "float");
        body.put("dimensions", properties.getEmbeddingDimensions());
        ArrayNode input = body.putArray("input");
        batch.forEach(input::add);

        String raw;
        try {
            raw = restClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new AiUnavailableException("embedding 调用失败: " + e.getMessage(), e);
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != batch.size()) {
                throw new AiUnavailableException("embedding 返回条数不符: 期望 "
                        + batch.size() + " 实际 " + data.size());
            }
            List<float[]> vectors = new ArrayList<>(batch.size());
            for (JsonNode item : data) {
                JsonNode arr = item.path("embedding");
                float[] v = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    v[i] = (float) arr.get(i).asDouble();
                }
                if (v.length != dimensions()) {
                    // 服务端忽略了 dimensions 参数时，存进库的维度会和四元组对不上，
                    // 与其留到检索时算出一堆无意义的余弦，不如当场判失败
                    throw new AiUnavailableException("embedding 维度不符: 期望 "
                            + dimensions() + " 实际 " + v.length);
                }
                vectors.add(v);
            }
            return vectors;
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUnavailableException("embedding 返回体解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int dimensions() {
        return properties.getEmbeddingDimensions();
    }

    @Override
    public double minScore() {
        return properties.getMinScore();
    }

    @Override
    public String name() {
        return properties.getEmbeddingModel();
    }
}
