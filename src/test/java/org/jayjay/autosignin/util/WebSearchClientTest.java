package org.jayjay.autosignin.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WebSearchClientTest {

    private HttpServer server;
    private volatile String responseBody;
    private volatile int responseStatus;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> query = new AtomicReference<>();

    @Before
    public void setUp() throws IOException {
        responseStatus = 200;
        responseBody = result("https://example.com/help", "切问帮助", "支持深度检索与资料整理。");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            requests.incrementAndGet();
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/search?" + exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void returnsSearchSnippetsAndSourcesWithoutNavigationOrInvalidLinks() {
        responseBody += "<a href='https://navigation.example'>导航里的文字</a>"
                + result("javascript:alert(1)", "伪造链接", "不应作为资料返回")
                + result("https://example.com/empty", "只有标题", "");

        String material = client().search(queries("切问 官网 & 功能"));

        assertTrue(query.get().contains("q=切问 官网 & 功能"));
        assertTrue(material.contains("支持深度检索与资料整理"));
        assertTrue(material.contains("检索来源：\nhttps://example.com/help"));
        assertFalse(material.contains("导航里的文字"));
        assertFalse(material.contains("伪造链接"));
        assertFalse(material.contains("只有标题"));
    }

    @Test
    public void rejectsBlockedSearchEvenWhenThePageMentionsTheProduct() {
        responseBody = "<html><body>切问：请完成验证以继续搜索</body></html>";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client().search(queries("切问 官网")));

        assertTrue(error.getMessage().contains("没有返回可用资料或来源"));
    }

    @Test
    public void followsSearchRedirectsBeforeParsingResults() {
        WebSearchClient redirected = new WebSearchClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");

        String material = redirected.search(queries("切问 官网"));

        assertEquals(1, requests.get());
        assertTrue(query.get().contains("q=切问 官网"));
        assertTrue(material.contains("https://example.com/help"));
    }

    @Test
    public void reportsHttpFailureInsteadOfUsingTheErrorPage() {
        responseStatus = 429;

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client().search(queries("切问 官网")));

        assertTrue(error.getMessage().contains("HTTP 429"));
    }

    @Test
    public void boundsSearchRequestsWhenAllQueriesFail() {
        responseBody = "<html><body>没有搜索结果</body></html>";

        assertThrows(IllegalStateException.class,
                () -> client().search(queries("第一个查询", "第二个查询", "第三个查询")));

        assertEquals(2, requests.get());
    }

    private WebSearchClient client() {
        return new WebSearchClient("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
    }

    private static Set<String> queries(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    private static String result(String url, String title, String summary) {
        return "<li class='b_algo'><h2><a href='" + url + "'>" + title + "</a></h2>"
                + "<div class='b_caption'><p>" + summary + "</p></div></li>";
    }
}
