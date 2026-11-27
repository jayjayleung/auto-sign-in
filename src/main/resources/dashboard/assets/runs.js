// 运行历史列表页与单次运行详情页。
import { api } from './api.js';
import { card, details, duration, esc, kv, logBlock, pageHeader, pill, table } from './util.js';
import { currentQuery, link, navigate } from './router.js';

const PAGE_SIZE = 20;

/** 运行历史列表：跨实例，按时间倒序，支持只看失败和按实例筛选。 */
export async function renderRuns(container, params, context) {
    const query = currentQuery();
    const offset = Number(query.get('offset') || 0) || 0;
    const onlyFailed = query.get('onlyFailed') === 'true';
    const instance = query.get('instance') || 'all';

    const [data, instanceList] = await Promise.all([
        api.runs({ offset, limit: PAGE_SIZE, onlyFailed, instance }),
        api.instances()
    ]);
    const names = ['all', ...(instanceList.instances || []).map((item) => item.name)];

    const filters = `<div class="filters">
        <label class="check">
            <input type="checkbox" id="only-failed" ${onlyFailed ? 'checked' : ''}> 只看有异常
        </label>
        <label class="check">
            实例
            <select id="instance-filter">
                ${names.map((name) => `<option value="${esc(name)}" ${name === instance ? 'selected' : ''}>${esc(name === 'all' ? '全部' : name)}</option>`).join('')}
            </select>
        </label>
    </div>`;

    const rows = (data.runs || []).map((run) => [
        link(`/runs/${encodeURIComponent(run.instance)}/${encodeURIComponent(run.file)}`, esc(run.finishedAt)),
        esc(run.instance),
        { num: true, html: esc(duration(run.durationSeconds)) },
        { num: true, html: run.exceptionCount > 0 ? pill(`${run.exceptionCount}`, 'bad') : '0' },
        { num: true, html: esc(run.sectionCount || 0) },
        esc((run.sectionTitles || []).join('、') || '—')
    ]);

    const pageQuery = (nextOffset) => {
        const next = new URLSearchParams();
        if (nextOffset > 0) next.set('offset', String(nextOffset));
        if (onlyFailed) next.set('onlyFailed', 'true');
        if (instance !== 'all') next.set('instance', instance);
        const suffix = next.toString();
        return `/runs${suffix ? '?' + suffix : ''}`;
    };

    const pager = `<div class="pager">
        ${offset > 0 ? link(pageQuery(Math.max(0, offset - PAGE_SIZE)), '← 上一页', 'btn') : '<span></span>'}
        <span class="sub">第 ${Math.floor(offset / PAGE_SIZE) + 1} 页 · 本页 ${data.returned || 0} 条</span>
        ${(data.runs || []).length === PAGE_SIZE ? link(pageQuery(offset + PAGE_SIZE), '下一页 →', 'btn') : '<span></span>'}
    </div>`;

    container.innerHTML = pageHeader('运行历史', `按结束时间倒序 · 每页 ${PAGE_SIZE} 条`)
        + card('', filters + table([
            { label: '结束时间' },
            { label: '实例' },
            { label: '耗时', num: true },
            { label: '异常', num: true },
            { label: '站点数', num: true },
            { label: '站点' }
        ], rows, '没有匹配的运行记录') + pager);

    const failedBox = container.querySelector('#only-failed');
    failedBox.addEventListener('change', () => {
        const next = new URLSearchParams();
        if (failedBox.checked) next.set('onlyFailed', 'true');
        if (instance !== 'all') next.set('instance', instance);
        const suffix = next.toString();
        navigate(`/runs${suffix ? '?' + suffix : ''}`);
    });
    const instanceBox = container.querySelector('#instance-filter');
    instanceBox.addEventListener('change', () => {
        const next = new URLSearchParams();
        if (onlyFailed) next.set('onlyFailed', 'true');
        if (instanceBox.value !== 'all') next.set('instance', instanceBox.value);
        const suffix = next.toString();
        navigate(`/runs${suffix ? '?' + suffix : ''}`);
    });
}

/** 单次运行详情：与推送内容完全一致的分组结果。 */
export async function renderRunDetail(container, params) {
    let data;
    try {
        data = await api.run(params.instance, params.file);
    } catch (e) {
        container.innerHTML = `<div class="card"><h2>读取失败</h2>
            <p class="sub">${esc(e.message)}</p>
            <p>${link('/runs', '返回历史列表')}</p></div>`;
        return;
    }
    const summary = data.summary || {};
    const sections = data.sections || [];

    const sectionCards = sections.length === 0
        ? `<div class="empty">这次运行没有产生任何站点结果</div>`
        : sections.map(renderSectionCard).join('');

    container.innerHTML = pageHeader(`${data.instance} · 单次运行`,
        `${esc(summary.startedAt)} → ${esc(summary.finishedAt)} · 耗时 ${duration(summary.durationSeconds)}`,
        `${link(`/instances/${encodeURIComponent(data.instance)}`, '实例详情', 'btn')}
         ${link('/runs', '返回历史列表', 'btn')}`)
        + card('', kv([
            ['实例', esc(data.instance)],
            ['开始', esc(summary.startedAt)],
            ['结束', esc(summary.finishedAt)],
            ['耗时', duration(summary.durationSeconds)],
            ['异常', summary.exceptionCount > 0 ? pill(`${summary.exceptionCount} 个`, 'bad') : pill('无', 'ok')],
            ['站点数', esc(summary.sectionCount || sections.length)]
        ]))
        + `<div class="section-title">各站点结果</div>`
        + `<div class="grid cols-2">${sectionCards}</div>`
        + `<div class="section-title">纯文本（与推送一致）</div>`
        + card('', details('展开完整文本', `<div style="margin-top:8px">${logBlock(data.console, '（空）')}</div>`, true));
}

/** 单个站点的结果卡：每个 block 是一段，行内保留加粗。 */
function renderSectionCard(section) {
    const blocks = (section.blocks || []).map((block) => {
        const lines = (block.lines || []).map((line) => (line.parts || [])
            .map((part) => (part.bold ? `<strong>${esc(part.text)}</strong>` : esc(part.text)))
            .join(''))
            .join('<br>');
        return `<p class="block">${lines}</p>`;
    }).join('');
    return card(section.title, blocks || `<div class="empty">（空）</div>`);
}
