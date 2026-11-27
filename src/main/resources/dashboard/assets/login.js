// 登录视图：独立于主布局，登录成功后整页回到路由。
import { login } from './api.js';

export function renderLogin(container, onSuccess) {
    container.innerHTML = `
        <div class="login-wrap">
            <form class="login" id="login-form" autocomplete="on">
                <h1>签到看板</h1>
                <label for="u">账号</label>
                <input id="u" name="username" autocomplete="username" required>
                <label for="p">口令</label>
                <input id="p" name="password" type="password" autocomplete="current-password" required>
                <button type="submit" id="login-btn">登录</button>
                <div class="err" id="login-err"></div>
            </form>
        </div>`;

    const form = container.querySelector('#login-form');
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const button = container.querySelector('#login-btn');
        const err = container.querySelector('#login-err');
        button.disabled = true;
        err.textContent = '';
        const result = await login(
            container.querySelector('#u').value,
            container.querySelector('#p').value
        );
        button.disabled = false;
        if (result.ok) {
            onSuccess();
            return;
        }
        err.textContent = result.error || '登录失败';
    });
}
