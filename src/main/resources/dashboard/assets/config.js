// 配置页：多实例并排对比，便于发现「某台漏配了通知渠道」这类问题。
import { api } from './api.js';
import { card, esc, pageHeader, pill, table } from './util.js';

export async function renderConfig(container) {
    const data = await api.config();
    const instances = data.instances || [];

    const groupRows = (data.groups || []).map((row) => {
        const cells = instances.map((instance, index) => {
            const cell = (row.cells || [])[index] || { present: 0, total: 0 };
            const full = cell.present >= cell.total;
            const kind = full ? 'ok' : cell.present === 0 ? 'mute' : 'warn';
            return {
                num: true,
                html: `${esc(cell.present)}/${esc(cell.total)} ${pill(full ? '齐全' : cell.present === 0 ? '未配' : '缺项', kind)}`
            };
        });
        return [esc(row.name), ...cells];
    });

    const switchRows = (data.switches || []).map((row) => {
        const cells = instances.map((instance, index) => {
            const cell = (row.cells || [])[index] || { value: '' };
            const on = String(cell.value).includes('开');
            return { html: pill(cell.value || '—', on ? 'ok' : 'mute') };
        });
        return [esc(row.key), ...cells];
    });

    const instanceHeaders = instances.map((instance) => ({ label: instance.name }));

    container.innerHTML = pageHeader('配置对比', '只显示是否已配置与开关状态，不回显任何配置值')
        + card('配置项',
            table([{ label: '配置组' }, ...instanceHeaders], groupRows)
            + `<div class="sub" style="margin-top:12px">这里只统计「填了几项」，具体值不会出现在页面上。</div>`)
        + `<div class="section-title">开关</div>`
        + card('', table([{ label: '开关' }, ...instanceHeaders], switchRows))
        + `<div class="section-title">说明</div>`
        + card('', `<div class="sub">「默认(开)」「默认(关)」表示该键留空，程序实际取的是这个默认值。
            要改配置请编辑对应实例的 env 文件后重建容器，页面不提供在线修改。</div>`);
}
