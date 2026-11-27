// 接口封装。统一处理 401（跳登录页）和错误信息。
// 所有请求都是 GET：看板只读，没有写操作。

let onUnauthorized = () => {};

/** 注册未登录时的回调，由 app 注入。 */
export function setUnauthorizedHandler(handler) {
    onUnauthorized = handler;
}

async function request(path) {
    const response = await fetch(path, { headers: { Accept: 'application/json' } });
    if (response.status === 401) {
        onUnauthorized();
        throw new Error('未登录');
    }
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(data.error || `请求失败（HTTP ${response.status}）`);
    }
    return data;
}

export const api = {
    overview: () => request('/api/overview'),
    instances: () => request('/api/instances'),
    instance: (name, options = {}) => {
        const query = new URLSearchParams();
        if (options.history) query.set('history', options.history);
        if (options.logs != null) query.set('logs', options.logs);
        if (options.tail) query.set('tail', options.tail);
        const suffix = query.toString();
        return request(`/api/instances/${encodeURIComponent(name)}${suffix ? '?' + suffix : ''}`);
    },
    runs: (options = {}) => {
        const query = new URLSearchParams();
        if (options.offset != null) query.set('offset', options.offset);
        if (options.limit) query.set('limit', options.limit);
        if (options.onlyFailed) query.set('onlyFailed', 'true');
        if (options.instance && options.instance !== 'all') query.set('instance', options.instance);
        const suffix = query.toString();
        return request(`/api/runs${suffix ? '?' + suffix : ''}`);
    },
    run: (instance, file) =>
        request(`/api/runs/${encodeURIComponent(instance)}/${encodeURIComponent(file)}`),
    config: () => request('/api/config'),
    pipeline: () => request('/api/pipeline')
};

/** 登录；成功后由调用方重新渲染。 */
export async function login(username, password) {
    const response = await fetch('/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
    });
    if (response.ok) {
        return { ok: true };
    }
    const data = await response.json().catch(() => ({}));
    return { ok: false, error: data.error || '登录失败' };
}

/** 当前是否已登录：用最轻的接口探一次。 */
export async function probe() {
    try {
        await api.overview();
        return true;
    } catch (e) {
        return false;
    }
}
