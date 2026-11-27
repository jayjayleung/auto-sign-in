// 通用工具：转义、格式化、DOM 构造。
// 所有文本渲染都必须经过 esc，接口数据里可能有用户可控内容（站点标题、UID 等）。

/** HTML 转义，页面拼接字符串时唯一的防线。 */
export function esc(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/** 状态药丸。 */
export function pill(text, kind = 'mute') {
    return `<span class="pill ${kind}">${esc(text)}</span>`;
}

/** 秒数转成「1 分 20 秒」这样的可读时长。 */
export function duration(seconds) {
    const total = Number(seconds) || 0;
    if (total < 60) {
        return `${total} 秒`;
    }
    const minutes = Math.floor(total / 60);
    const rest = total % 60;
    return rest === 0 ? `${minutes} 分` : `${minutes} 分 ${rest} 秒`;
}

/** 字节数转 KB / MB。 */
export function bytes(size) {
    const value = Number(size) || 0;
    if (value < 1024) {
        return `${value} B`;
    }
    if (value < 1024 * 1024) {
        return `${Math.round(value / 1024)} KB`;
    }
    return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

/** 表格：headers 为 [{label, num?}]，rows 为二维数组，单元格可以是 HTML 片段。 */
export function table(headers, rows, emptyText = '暂无数据') {
    if (!rows || rows.length === 0) {
        return `<div class="empty">${esc(emptyText)}</div>`;
    }
    const head = headers
        .map((item) => `<th class="${item.num ? 'num' : ''}">${esc(item.label)}</th>`)
        .join('');
    const body = rows
        .map((cells) => {
            const tds = cells
                .map((cell) => {
                    if (cell && typeof cell === 'object') {
                        return `<td class="${cell.num ? 'num' : ''}">${cell.html}</td>`;
                    }
                    return `<td>${cell == null ? '' : cell}</td>`;
                })
                .join('');
            return `<tr>${tds}</tr>`;
        })
        .join('');
    return `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

/** 键值对列表。 */
export function kv(pairs) {
    const items = pairs
        .filter((pair) => pair && pair.length >= 2)
        .map(([key, value]) => `<span class="k">${esc(key)}</span><span>${value}</span>`)
        .join('');
    return `<div class="kv">${items}</div>`;
}

/** 折叠区，默认关闭。 */
export function details(summary, body, open = false) {
    return `<details ${open ? 'open' : ''}>
        <summary class="sub" style="cursor:pointer">${esc(summary)}</summary>
        ${body}
    </details>`;
}

/** 等宽日志块。 */
export function logBlock(text, emptyText = '（空）') {
    if (!text) {
        return `<div class="empty">${esc(emptyText)}</div>`;
    }
    return `<div class="log">${esc(text)}</div>`;
}

/** 卡片外壳。 */
export function card(title, body, options = {}) {
    const head = title
        ? `<div class="head"><h2>${esc(title)}</h2>${options.aside || ''}</div>`
        : '';
    return `<div class="card">${head}${body}</div>`;
}

/** 页面主标题区。 */
export function pageHeader(title, subtitle, actions = '') {
    return `<div class="topbar">
        <div>
            <h1>${esc(title)}</h1>
            <div class="sub">${subtitle}</div>
        </div>
        <div class="actions">${actions}</div>
    </div>`;
}
