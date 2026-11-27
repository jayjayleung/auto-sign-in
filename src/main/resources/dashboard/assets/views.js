// 共享的小视图片段：互评闭环、配置开关列表等，多个页面复用。
import { esc, kv, pill, table } from './util.js';

/** 互评闭环。三账号共用一份轮次文件，本地运行时会退化成不可用。 */
export function pipelineCard(pipeline) {
    if (!pipeline || !pipeline.available) {
        return `<div class="empty">共享轮次文件不可读（本地运行时会退化成单账号，属正常）</div>`;
    }
    const rows = (pipeline.byAccount || []).map((row) => [
        esc(row.uid),
        { num: true, html: esc(row.asked) },
        { num: true, html: esc(row.answered) },
        { num: true, html: esc(row.gotAdopted) }
    ]);
    return kv([
        ['账号数', esc(pipeline.accounts)],
        ['轮次', esc(pipeline.rounds)],
        ['已采纳', esc(pipeline.adopted)],
        ['已互评', esc(pipeline.commented)],
        ['外部回答者', esc(pipeline.externalAnswerers || 0)],
        ['更新于', esc(pipeline.updatedAt || '—')]
    ]) + `<div style="margin-top:12px">${table([
        { label: '账号 UID' },
        { label: '提问', num: true },
        { label: '回答', num: true },
        { label: '被采纳', num: true }
    ], rows)}</div>`;
}

/** 开关列表：值只有开/关/默认，不含任何配置值。 */
export function switchTable(switches) {
    const rows = (switches || []).map((item) => {
        const on = String(item.value).includes('开');
        return [esc(item.key), pill(item.value, on ? 'ok' : 'mute')];
    });
    return table([{ label: '开关' }, { label: '状态' }], rows);
}

/** 配置组：只显示「配了几项/共几项」。 */
export function configTable(groups) {
    const rows = (groups || []).map((group) => {
        const full = group.present >= group.total;
        const kind = full ? 'ok' : group.present === 0 ? 'mute' : 'warn';
        return [
            esc(group.name),
            { num: true, html: `${group.present}/${group.total}` },
            pill(full ? '已配置' : group.present === 0 ? '未配置' : '缺项', kind)
        ];
    });
    return table([
        { label: '配置项' },
        { label: '已填', num: true },
        { label: '状态' }
    ], rows);
}
