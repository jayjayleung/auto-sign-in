package org.jayjay.autosignin.util;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AiClientTest {

    private HttpServer server;
    private Map<String, String> environment;
    private volatile String responseBody;
    private final AtomicReference<JSONObject> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @Before
    public void setUp() throws IOException {
        responseBody = searchResponse(true, true, false).toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(JSONUtil.parseObj(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        environment = new HashMap<String, String>();
        environment.put("AI_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        environment.put("AI_API_KEY", "test-key");
        environment.put("AI_MODEL", "test-model");
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void searchesWithNativeToolAndReturnsFactsAndSources() {
        String result = client().search("优先查询产品官网", "查一下切问的主要功能");

        JSONObject body = requestBody.get();
        assertEquals("test-model", body.getStr("model"));
        assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).getStr("type"));
        assertEquals("required", body.getStr("tool_choice"));
        assertEquals("优先查询产品官网", body.getStr("instructions"));
        assertEquals("查一下切问的主要功能", body.getStr("input"));
        assertEquals("Bearer test-key", authorization.get());
        assertTrue(result.contains("切问提供深度检索"));
        assertTrue(result.contains("https://example.com/help"));
        assertFalse(result.contains("内部推理"));
    }

    @Test
    public void acceptsCitationSourcesWhenToolOmitsSourceList() {
        responseBody = searchResponse(true, false, true).toString();

        assertTrue(client().search("查官网", "切问").contains("https://example.com/help"));
    }

    @Test
    public void rejectsAnswerThatOnlyClaimsToHaveSearched() {
        responseBody = searchResponse(false, false, true).toString();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client().search("查官网", "切问"));

        assertTrue(error.getMessage().contains("服务未执行联网搜索"));
    }

    @Test
    public void rejectsSearchWithoutVerifiableSources() {
        responseBody = searchResponse(true, false, false).toString();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client().search("查官网", "切问"));

        assertTrue(error.getMessage().contains("没有返回产品资料或来源"));
    }

    @Test
    public void rejectsIncompleteSearchEvenWhenItContainsText() {
        responseBody = searchResponse(true, true, false).set("status", "incomplete").toString();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client().search("查官网", "切问"));

        assertTrue(error.getMessage().contains("联网搜索未完成"));
    }

    @Test
    public void supportsConfiguredFullChatEndpointForSearch() {
        environment.put("AI_BASE_URL", environment.get("AI_BASE_URL") + "/chat/completions");

        assertTrue(client().search("查官网", "切问").contains("切问提供深度检索"));
    }

    private AiClient client() {
        return new AiClient(environment, "");
    }

    private static JSONObject searchResponse(boolean withSearch, boolean withSources, boolean withCitation) {
        JSONArray output = JSONUtil.createArray();
        output.add(JSONUtil.createObj().set("type", "reasoning")
                .set("content", JSONUtil.createArray().put(JSONUtil.createObj()
                        .set("type", "reasoning_text").set("text", "内部推理"))));
        if (withSearch) {
            JSONObject action = JSONUtil.createObj().set("type", "search");
            if (withSources) {
                action.set("sources", JSONUtil.createArray().put(JSONUtil.createObj()
                        .set("type", "url").set("url", "https://example.com/help")));
            }
            output.add(JSONUtil.createObj().set("type", "web_search_call")
                    .set("status", "completed").set("action", action));
        }
        JSONObject text = JSONUtil.createObj().set("type", "output_text").set("text", "切问提供深度检索。");
        if (withCitation) {
            text.set("annotations", JSONUtil.createArray().put(JSONUtil.createObj()
                    .set("type", "url_citation").set("url", "https://example.com/help")));
        }
        output.add(JSONUtil.createObj().set("type", "message").set("content", JSONUtil.createArray().put(text)));
        return JSONUtil.createObj().set("status", "completed").set("output", output);
    }
}
