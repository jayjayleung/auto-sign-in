// 实例列表页与实例详情页。
import { api } from './api.js';
import { bytes, card, details, duration, esc, kv, logBlock, pageHeader, pill, table } from './util.js';
import { link, navigate } from './router.js';
import { configTable, switchTable } from './views.js';

/** 实例列表：只有摘要，点进去看详情。 */
export async function renderInstances(container) {
    const data = await api.instances();
    const rows = (data.instances || []).map((item) => {
        const lastRun = item.lastRun || {};
        return [
            link(`/instances/${encodeURIComponent(item.name)}`, esc(item.name)),
            item.available ? pill('可读', 'ok') : pill('不可读', 'warn'),
            item.ranToday ? pill('今日已跑', 'ok') : pill(item.siemens && item.siemens.lastRunDate ? item.siemens.lastRunDate : '无记录', 'warn'),
            lastRun.hasRecord ? esc(lastRun.finishedAt) : pill('无', 'mute'),
            { num: true, html: lastRun.durationSeconds ? esc(duration(lastRun.durationSeconds)) : '—' },
            { num: true, html: lastRun.exceptionCount > 0 ? pill(`${lastRun.exceptionCount}`, 'bad') : '0' },
            { num: true, html: esc(item.contentBankReady || 0) },
            item.paused ? pill('停写', 'bad') : pill('正常', 'ok')
        ];
    });
    container.innerHTML = pageHeader('实例', `共 ${data.total || 0} 个`)
        + card('', table([
            { label: '实例' },
            { label: '状态' },
            { label: '西门子' },
            { label: '最近运行' },
            { label: '耗时', num: true },
            { label: '异常', num: true },
            { label: '内容库', num: true },
            { label: '风控' }
        ], rows, '还没有实例'));
}

/** 实例详情。 */
export async function renderInstanceDetail(container, params) {
    const name = params.name;
    let data;
    try {
        data = await api.instance(name, { history: 10, logs: 3, tail: 150 });
    } catch (e) {
        container.innerHTML = `<div class="card"><h2>读取失败</h2>
            <p class="sub">${esc(e.message)}</p>
            <p>${link('/', '返回概览')}</p></div>`;
        return;
    }
    if (!data.available) {
        container.innerHTML = `<div class="card"><h2>${esc(name)}</h2>
            <p class="sub">${esc(data.reason || '状态目录不可访问')}</p>
            <p>${link('/instances', '返回实例列表')}</p></div>`;
        return;
    }

    const siemens = data.siemens || {};
    const bank = data.contentBank || {};
    const watcha = data.watcha || {};
    const lastRun = data.lastRun || {};
    const streak = siemens.storyStreak;

    const statusCard = card('', kv([
        ['西门子标记', esc(siemens.lastRunDate || '无')],
        ['挑战日期', esc(siemens.challengeDate || '无')],
        ['故事连败', streak == null ? pill('—', 'mute') : pill(`连败 ${streak}`, streak >= 2 ? 'bad' : streak > 0 ? 'warn' : 'ok')],
        ['西币回执', siemens.coinNotices == null ? '—' : `${siemens.coinNotices} 条（累计去重）`],
        ['西门子会话', siemens.hasSession ? pill('已保存', 'ok') : pill('无', 'warn')],
        ['最近运行', lastRun.hasRecord
            ? `${esc(lastRun.finishedAt)} · ${duration(lastRun.durationSeconds)} ${lastRun.exceptionCount > 0 ? pill(`${lastRun.exceptionCount} 异常`, 'bad') : pill('正常', 'ok')}`
            : pill('等下一次运行', 'mute')]
    ]), { aside: lastRun.hasRecord ? '' : '' });

    const bankRows = (bank.rows || []).map((row) => [
        esc(row.kind),
        { num: true, html: esc(row.ready) },
        { num: true, html: esc(row.used) },
        { num: true, html: esc(row.rejected) }
    ]);
    const bankCard = card('内容库', bank.available
        ? `${kv([['可用', pill(`${bank.ready}`, bank.ready > 0 ? 'ok' : 'warn')],
                   ['已用', esc(bank.used)],
                   ['作废', pill(`${bank.rejected}`, bank.rejected > 0 ? 'warn' : 'mute')],
                   ['更新于', esc(bank.updatedAt || '—')]])}
           <div style="margin-top:12px">${table([
               { label: '内容类型' },
               { label: '可用', num: true },
               { label: '已用', num: true },
               { label: '作废', num: true }
           ], bankRows)}</div>`
        : `<div class="empty">没有内容库文件</div>`);

    const watchaCard = card('观猹', kv([
        ['会话', watcha.hasSession ? pill(`至 ${watcha.refreshExpiresAt || '未知'}`, 'ok') : pill('无会话', 'warn')],
        ['access 过期', esc(watcha.accessExpiresAt || '—')],
        ['猹评台账', `${esc(watcha.publishedCount || 0)} 条`],
        ['风控', watcha.paused
            ? pill(`已停写（strikes ${watcha.strikes}）`, 'bad')
            : pill(watcha.strikes > 0 ? `strikes ${watcha.strikes}` : '无违规', watcha.strikes > 0 ? 'warn' : 'ok')]
    ]));

    const historyRows = (data.history || []).map((record) => [
        link(`/runs/${encodeURIComponent(record.instance || name)}/${encodeURIComponent(record.file)}`,
            esc(record.finishedAt || record.file)),
        { num: true, html: esc(duration(record.durationSeconds)) },
        { num: true, html: record.exceptionCount > 0 ? pill(`${record.exceptionCount}`, 'bad') : '0' },
        esc((record.sectionTitles || []).join('、') || '—')
    ]);

    const historyCard = card('运行历史',
        table([
            { label: '结束时间' },
            { label: '耗时', num: true },
            { label: '异常', num: true },
            { label: '站点' }
        ], historyRows, '还没有运行记录，跑一次后这里会出现')
        + `<div style="margin-top:12px">${link(`/runs?instance=${encodeURIComponent(name)}`, '查看全部历史 →')}</div>`);

    const logs = data.logs || [];
    const logCard = card('运行日志', logs.length === 0
        ? `<div class="empty">还没有日志文件。下一次定时运行后会出现，容器重建也不会丢。</div>`
        : logs.map((item, index) => details(
            `${item.file} · ${bytes(item.sizeBytes)} · ${item.updatedAt}`,
            `<div style="margin-top:8px">${logBlock(item.tail, '（空）')}</div>`,
            index === 0
        )).join(''));

    const configCard = card('配置与开关', details('配置项（只显示是否已填）',
        `<div style="margin-top:8px">${configTable(data.config && data.config.groups)}</div>`)
        + details('开关',
        `<div style="margin-top:8px">${switchTable(data.config && data.config.switches)}</div>`));

    container.innerHTML = pageHeader(name,
        lastRun.hasRecord ? `最近运行 ${esc(lastRun.finishedAt)}` : '还没有运行记录',
        `${link('/runs?instance=' + encodeURIComponent(name), '历史记录', 'btn')}
         ${link('/instances', '返回列表', 'btn')}`)
        + `<div class="grid cols-2">${statusCard}${bankCard}${watchaCard}${historyCard}</div>`
        + `<div class="section-title">日志</div>`
        + logCard
        + `<div class="section-title">配置</div>`
        + configCard;
}
