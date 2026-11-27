package org.jayjay.autosignin.dashboard;

import org.junit.Assume;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 前端资源的守卫测试。
 * <p>
 * 前端代码没有构建步骤，语法错误只会在浏览器里静默失败（页面一直停在「加载中」），
 * 所以这里在测试阶段就做语法校验和引用校验。语法检查交给 {@code node --check}，
 * 比自己手写词法分析可靠；没有 node 时跳过而不是失败。
 */
public class DashboardAssetsTest {

    /** 页面会用到的全部前端模块。 */
    private static final String[] MODULES = {
            "app.js", "api.js", "router.js", "util.js", "login.js",
            "overview.js", "instance.js", "runs.js", "config.js", "views.js"
    };

    @Test
    public void 所有前端模块都存在且可读() throws Exception {
        for (String module : MODULES) {
            byte[] content = read("/dashboard/assets/" + module);
            assertNotNull("缺少前端模块：" + module, content);
            assertTrue("模块为空：" + module, content.length > 0);
        }
        assertNotNull(read("/dashboard/index.html"));
        assertNotNull(read("/dashboard/assets/app.css"));
    }

    @Test
    public void 模块通过node语法检查() throws Exception {
        Path node = findNode();
        Assume.assumeTrue("环境里没有 node，跳过语法检查", node != null);

        List<String> failures = new ArrayList<String>();
        for (String module : MODULES) {
            Path temp = Files.createTempFile("dash-", "-" + module);
            try {
                Files.write(temp, read("/dashboard/assets/" + module));
                ProcessBuilder builder = new ProcessBuilder(node.toString(), "--check", temp.toString());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    failures.add(module + " 检查超时");
                } else if (process.exitValue() != 0) {
                    failures.add(module + "：\n" + output);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        assertTrue("前端模块语法错误：\n" + String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void 模块只引用存在的同级模块() throws Exception {
        List<String> missing = new ArrayList<String>();
        for (String module : MODULES) {
            String source = new String(read("/dashboard/assets/" + module), StandardCharsets.UTF_8);
            for (String target : importsOf(source)) {
                if (read("/dashboard/assets/" + target) == null) {
                    missing.add(module + " → " + target);
                }
            }
        }
        assertTrue("以下 import 指向不存在的模块：" + missing, missing.isEmpty());
    }

    @Test
    public void 页面入口引用了打包后的资源路径() throws Exception {
        String html = new String(read("/dashboard/index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("/assets/app.js"));
        assertTrue(html.contains("/assets/app.css"));
        assertTrue(html.contains("type=\"module\""));
    }

    @Test
    public void 前端不硬编码任何像密钥的常量() throws Exception {
        // 前端代码会被任何人直接下载，出现长随机串意味着凭据泄漏
        for (String module : MODULES) {
            String source = new String(read("/dashboard/assets/" + module), StandardCharsets.UTF_8);
            assertFalse("模块 " + module + " 里疑似硬编码了凭据",
                    source.matches("(?s).*['\"][A-Za-z0-9+/]{32,}['\"].*"));
        }
    }

    /** 抽取所有 {@code from './x.js'} 形式的相对引用。 */
    private static List<String> importsOf(String source) {
        List<String> targets = new ArrayList<String>();
        int index = 0;
        while ((index = source.indexOf("from '", index)) >= 0) {
            int start = index + "from '".length();
            int end = source.indexOf('\'', start);
            if (end < 0) {
                break;
            }
            String target = source.substring(start, end);
            if (target.startsWith("./")) {
                targets.add(target.substring(2));
            }
            index = end + 1;
        }
        return targets;
    }

    private static Path findNode() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = java.nio.file.Paths.get(dir, "node");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] read(String resource) {
        try (InputStream input = DashboardAssetsTest.class.getResourceAsStream(resource)) {
            return input == null ? null : readAll(input);
        } catch (Exception e) {
            return null;
        }
    }
}
