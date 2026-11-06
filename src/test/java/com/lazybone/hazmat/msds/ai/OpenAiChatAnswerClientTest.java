package com.lazybone.hazmat.msds.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.dto.MsdsHit;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用本机 HttpServer 冒充 OpenAI 兼容端，验证真实 HTTP 请求的形状。
 *
 * 服务层的测试用 mock 替换掉了客户端，覆盖不到「请求体怎么拼、路径怎么拼、
 * 返回体怎么解析」这一段；这里不依赖网络和模型，也不会因为模型换版本而随机红。
 */
class OpenAiChatAnswerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    private volatile int responseStatus = 200;
    private volatile String responseBody =
            "{\"choices\":[{\"message\":{\"content\":\" 应立即通风并撤离 [1]。 \"}}]}";

    private HttpServer server;
    private AiProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        properties = new AiProperties();
        properties.setChatEnabled(true);
        properties.setChatModel("qwen2.5:3b");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setChatTimeoutMs(3000);
        properties.setChatMaxTokens(800);
        properties.setChatTemperature(0.1);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsModelAndSnippetsInOpenAiShape() throws Exception {
        String answer = client().answer("误食怎么办", List.of(
                hit(1L, "67-56-1", "甲醇", 4, "急救措施", "食入：立即漱口，就医。"),
                hit(2L, "67-64-1", "丙酮", 4, "急救措施", "吸入：转移至新鲜空气处。")));

        // 返回内容去掉首尾空白
        assertThat(answer).isEqualTo("应立即通风并撤离 [1]。");
        assertThat(lastPath.get()).isEqualTo("/v1/chat/completions");

        JsonNode body = objectMapper.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("qwen2.5:3b");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.1);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(800);
        assertThat(body.path("stream").asBoolean()).isFalse();

        JsonNode messages = body.path("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).contains("只能依据");

        String user = messages.get(1).path("content").asText();
        assertThat(user).contains("问题：误食怎么办");
        assertThat(user).contains("[1] 《甲醇》 第4节 急救措施（CAS 67-56-1）");
        assertThat(user).contains("[2] 《丙酮》");
        assertThat(user).contains("食入：立即漱口，就医。");
    }

    @Test
    void promptContainsAtMostFiveHits() throws Exception {
        List<MsdsHit> hits = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            hits.add(hit((long) i, "cas-" + i, "试剂" + i, i, "章节", "内容" + i));
        }

        client().answer("问题", hits);

        String user = objectMapper.readTree(lastBody.get())
                .path("messages").get(1).path("content").asText();
        assertThat(user).contains("[5] 《试剂5》");
        assertThat(user).doesNotContain("[6]");
        assertThat(user).doesNotContain("试剂6");
    }

    @Test
    void longSnippetIsTruncated() throws Exception {
        String longText = "甲".repeat(900);
        client().answer("问题", List.of(hit(1L, "cas", "试剂", 1, "章节", longText)));

        String user = objectMapper.readTree(lastBody.get())
                .path("messages").get(1).path("content").asText();
        // 700 字正文 + 省略号；第 701 字之后的内容不再发给模型
        assertThat(user).contains("甲".repeat(700) + "…");
        assertThat(user).doesNotContain("甲".repeat(701));
    }

    @Test
    void httpErrorBecomesUnavailable() {
        responseStatus = 500;
        responseBody = "{\"error\":\"upstream\"}";

        assertThatThrownBy(() -> client().answer("问题", List.of(hit(1L, "cas", "试剂", 1, "章节", "内容"))))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void blankContentBecomesUnavailable() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}";

        assertThatThrownBy(() -> client().answer("问题", List.of(hit(1L, "cas", "试剂", 1, "章节", "内容"))))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void malformedBodyBecomesUnavailable() {
        responseBody = "not json";

        assertThatThrownBy(() -> client().answer("问题", List.of(hit(1L, "cas", "试剂", 1, "章节", "内容"))))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void chatEndpointAndKeyCanDifferFromEmbedding() {
        // embedding 端点故意设成不可达：若仍然用 base-url/api-key，这个测试会直接失败
        properties.setBaseUrl("http://127.0.0.1:1/v1");
        properties.setApiKey("embedding-key");
        properties.setChatBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setChatApiKey("chat-key");

        String answer = client().answer("问题", List.of(hit(1L, "cas", "试剂", 1, "章节", "内容")));

        assertThat(answer).isEqualTo("应立即通风并撤离 [1]。");
        assertThat(lastPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer chat-key");
    }

    @Test
    void availableRequiresBothSwitchAndModelName() {
        assertThat(client().available()).isTrue();

        properties.setChatEnabled(false);
        assertThat(client().available()).isFalse();

        properties.setChatEnabled(true);
        properties.setChatModel(" ");
        assertThat(client().available()).isFalse();
    }

    private OpenAiChatAnswerClient client() {
        return new OpenAiChatAnswerClient(properties, objectMapper);
    }

    private static MsdsHit hit(Long chunkId, String casNo, String name,
                               int sectionNo, String title, String content) {
        return new MsdsHit(chunkId, casNo, name, sectionNo, title, content, 0.1, List.of("literal"),
                "MSDS", null);
    }
}
