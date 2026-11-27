// 应用入口：登录态判定、布局、路由注册。
import { probe, setUnauthorizedHandler } from './api.js';
import { esc, pageHeader } from './util.js';
import * as router from './router.js';
import { renderLogin } from './login.js';
import { renderOverview } from './overview.js';
import { renderInstanceDetail, renderInstances } from './instance.js';
import { renderRunDetail, renderRuns } from './runs.js';
import { renderConfig } from './config.js';

const NAV = [
    { path: '/', label: '概览' },
    { path: '/instances', label: '实例' },
    { path: '/runs', label: '运行历史' },
    { path: '/config', label: '配置对比' }
];

/** 渲染整体骨架：左侧导航 + 内容区。 */
function renderLayout(activePath) {
    const nav = NAV.map((item) => {
        // / 只在自己这一条时高亮，避免所有页面都亮着
        const active = item.path === '/'
            ? activePath === '/'
            : activePath === item.path || activePath.startsWith(item.path + '/');
        return `<a href="${item.path}" data-link class="nav-item ${active ? 'active' : ''}">${esc(item.label)}</a>`;
    }).join('');

    document.body.innerHTML = `
        <div class="layout">
            <aside class="sidebar">
                <div class="brand">签到看板</div>
                <nav>${nav}</nav>
                <div class="sidebar-foot">
                    <button id="refresh-btn">刷新</button>
                    <a href="/logout" class="btn">退出</a>
                </div>
            </aside>
            <main class="main">
                <div id="view"><div class="empty">加载中…</div></div>
            </main>
        </div>`;

    document.getElementById('refresh-btn').addEventListener('click', () => {
        router.navigate(router.currentPath(), { replace: true });
    });

    // 每次更换页面时刷新高亮
    const updateActive = () => {
        const path = router.currentPath();
        document.querySelectorAll('.nav-item').forEach((item) => {
            const href = item.getAttribute('href');
            const active = href === '/'
                ? path === '/'
                : path === href || path.startsWith(href + '/');
            item.classList.toggle('active', active);
        });
    };
    window.addEventListener('popstate', updateActive);
    document.addEventListener('click', (event) => {
        if (event.target.closest('a[data-link]')) {
            setTimeout(updateActive, 0);
        }
    });
    updateActive();
}

/** 包装视图函数，统一处理异常，避免一个页面报错整站白屏。 */
function guard(render) {
    return async (container, params) => {
        try {
            await render(container, params);
        } catch (e) {
            container.innerHTML = `<div class="card">
                <h2>页面加载失败</h2>
                <p class="sub">${esc(e.message || String(e))}</p>
                <p><a href="/" data-link>>返回概览</a></p>
            </div>`;
        }
    };
}

async function startApp() {
    const loggedIn = await probe();
    if (!loggedIn) {
        setUnauthorizedHandler(() => {
            renderLogin(document.body, startApp);
        });
        renderLogin(document.body, startApp);
        return;
    }

    setUnauthorizedHandler(() => {
        renderLogin(document.body, startApp);
    });

    renderLayout(router.currentPath());
    router.route('/', guard(renderOverview));
    router.route('/instances', guard(renderInstances));
    router.route('/instances/:name', guard(renderInstanceDetail));
    router.route('/runs', guard(renderRuns));
    router.route('/runs/:instance/:file', guard(renderRunDetail));
    router.route('/config', guard(renderConfig));
    router.start();
}

startApp();
