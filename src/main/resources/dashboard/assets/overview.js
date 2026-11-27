// 概览页：今日状态、异常、风控、最近运行。
import { api } from './api.js';
import { card, duration, esc, kv, pageHeader, pill, table } from './util.js';
import { link } from './router.js';
import { pipelineCard } from './views.js';

export async function renderOverview(container) {
    const data = await api.overview();
    const totals = data.totals || {};
    const instances = data.instances || [];

    const exceptionPill = totals.withTodayRecord === 0
        ? pill('暂无今日记录', 'mute')
        : totals.exceptions > 0
            ? pill(`${totals.exceptions} 个异常`, 'bad')
            : pill('无异常', 'ok');

    const statCards = `<div class="grid cols-3">
        ${card('今日西门子', `<div class="stat">${totals.ranToday || 0} / ${totals.instances || 0}</div>
            <div class="sub">${totals.ranToday === totals.instances ? '全部已执行' : '仍有实例未执行'}</div>`)}
        ${card('今日运行记录', `<div class="stat">${totals.withTodayRecord || 0} / ${totals.instances || 0}</div>
            <div class="sub">${exceptionPill}</div>`)}
        ${card('猹评风控', `<div class="stat">${totals.paused > 0 ? '已停写' : '正常'}</div>
            <div class="sub">${totals.paused > 0
                ? pill(`${totals.paused} 个实例停写中`, 'bad')
                : pill('无 strikes 触顶', 'ok')}</div>`)}
    </div>`;

    const rows = instances.map((item) => {
        const lastRun = item.lastRun || {};
        const health = (item.health && item.health.problems) || [];
        return [
            link(`/instances/${encodeURIComponent(item.name)}`, esc(item.name)),
            item.ranToday ? pill('今日已跑', 'ok') : pill(item.siemens && item.siemens.lastRunDate ? `上次 ${item.siemens.lastRunDate}` : '无记录', 'warn'),
            lastRun.hasRecord
                ? `${esc(lastRun.finishedAt)}<div class="sub">耗时 ${duration(lastRun.durationSeconds)}</div>`
                : pill('等待下一次运行', 'mute'),
            { num: true, html: lastRun.exceptionCount > 0 ? pill(`${lastRun.exceptionCount}`, 'bad') : esc(lastRun.exceptionCount || 0) },
            { num: true, html: esc(item.contentBankReady || 0) },
            health.length === 0 ? pill('正常', 'ok') : health.map((text) => pill(text, 'warn')).join(' ')
        ];
    });

    container.innerHTML = pageHeader('概览', `上海时间 ${esc(data.today)} · 刷新于 ${esc(data.generatedAt)}`)
        + statCards
        + `<div class="section-title">实例</div>`
        + card('', table([
            { label: '实例' },
            { label: '西门子' },
            { label: '最近运行' },
            { label: '异常', num: true },
            { label: '内容库可用', num: true },
            { label: '健康度' }
        ], rows))
        + `<div class="section-title">互评闭环</div>`
        + card('', pipelineCard(data.pipeline));
}
