// 路由：History API + 可深链接的路径。
// 用真实路径而不是 hash，这样「某条运行记录」的 URL 可以直接发给别人。

/** 路由表：pattern 用 :name 表示参数段。 */
const routes = [];

/** 注册路由。 */
export function route(pattern, render) {
    const segments = pattern.split('/').filter(Boolean);
    routes.push({ segments, render, pattern });
}

/** 把当前路径匹配成 { params, render }。 */
function match(path) {
    const parts = path.split('/').filter(Boolean);
    for (const item of routes) {
        if (item.segments.length !== parts.length) {
            continue;
        }
        const params = {};
        let ok = true;
        for (let index = 0; index < item.segments.length; index++) {
            const expected = item.segments[index];
            if (expected.startsWith(':')) {
                params[expected.slice(1)] = decodeURIComponent(parts[index]);
            } else if (expected !== parts[index]) {
                ok = false;
                break;
            }
        }
        if (ok) {
            return { params, render: item.render, pattern: item.pattern };
        }
    }
    return null;
}

/** 当前路径（不含 query）。 */
export function currentPath() {
    return window.location.pathname;
}

/** 当前查询参数。 */
export function currentQuery() {
    return new URLSearchParams(window.location.search);
}

/** 编程式跳转。 */
export function navigate(path, options = {}) {
    if (options.replace) {
        window.history.replaceState({}, '', path);
    } else {
        window.history.pushState({}, '', path);
    }
    dispatch();
}

/** 渲染当前路径。 */
export async function dispatch() {
    const path = currentPath();
    const found = match(path);
    const container = document.getElementById('view');
    if (!found) {
        container.innerHTML = `<div class="card">
            <h2>页面不存在</h2>
            <p class="sub">没有这个地址：<code>${path}</code></p>
            <p><a href="/" data-link>返回概览</a></p>
        </div>`;
        return;
    }
    await found.render(container, found.params);
}

/** 启动路由监听，并拦截站内链接避免整页刷新。 */
export function start() {
    window.addEventListener('popstate', dispatch);
    document.addEventListener('click', (event) => {
        const link = event.target.closest('a[data-link]');
        if (!link) {
            return;
        }
        const href = link.getAttribute('href');
        if (!href || href.startsWith('http')) {
            return;
        }
        event.preventDefault();
        navigate(href);
    });
    dispatch();
}

/** 生成站内链接。 */
export function link(href, text, className = '') {
    const cls = className ? ` class="${className}"` : '';
    return `<a href="${href}" data-link${cls}>${text}</a>`;
}
