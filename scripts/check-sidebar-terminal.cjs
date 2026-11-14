// Node 22+. Open a DSH chat first, then forward its debug WebView to a TCP port.
// This check uses the sidebar guide, types only into its own terminal and closes it.
const { once } = require('node:events');

async function checkInPage() {
    const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
    const visible = node => node && node.getClientRects().length && !node.closest('[aria-hidden="true"]');
    const firstVisible = (root, selector) => [...root.querySelectorAll(selector)].find(visible);
    async function until(find, label, timeout = 10000) {
        const deadline = Date.now() + timeout;
        do {
            const result = find();
            if (result) return result;
            await sleep(120);
        } while (Date.now() < deadline);
        throw new Error(label);
    }
    const panelSelector = '[data-sidebar-right-panel][data-sidebar-right-open]';
    const open = firstVisible(document, '[data-sidebar-right-expand]:not(:disabled)');
    const wasClosed = !firstVisible(document, panelSelector);
    if (wasClosed && !open) throw new Error('no-active-chat-sidebar');
    if (wasClosed) open.click();
    const panel = await until(() => firstVisible(document, panelSelector), 'sidebar-not-open');
    let guideId, tabId;
    let sent, read, cleaned = false;
    try {
        const guideButton = () => firstVisible(panel, '[data-sidebar-right-guide-entry="terminal"] button');
        if (!guideButton()) {
            const add = firstVisible(panel, '[data-dockkit-add-tab]');
            if (!add) throw new Error('sidebar-new-tab-unavailable');
            add.click();
        }
        const launch = await until(guideButton, 'terminal-guide-not-open');
        const paneId = launch.closest('[data-dockkit-pane]')?.dataset.dockkitPane;
        if (!paneId) throw new Error('terminal-guide-pane-unavailable');
        guideId = launch.closest('[data-sidebar-right-tab]').dataset.sidebarRightTab;
        const oldTabs = new Set([...panel.querySelectorAll('[data-dockkit-tab]')].map(tab => tab.dataset.dockkitTab));
        launch.click();
        const terminal = await until(() => {
            const strip = panel.querySelector('[data-dockkit-strip-tabs="' + CSS.escape(paneId) + '"]');
            const active = strip?.querySelector('[data-dockkit-tab][aria-selected="true"]')?.dataset.dockkitTab;
            if (!active || (oldTabs.has(active) && active !== guideId)) return;
            const body = panel.querySelector('[data-sidebar-right-tab="' + CSS.escape(active) + '"] [data-sidebar-terminal]');
            if (body) { tabId = active; return body; }
        }, 'new-terminal-tab-not-mounted');
        function issue() {
            const alert = terminal.querySelector('[role="alert"]');
            if (!alert) return;
            const text = alert.textContent;
            const known = text.match(/forkpty\(3\) failed|EAGAIN|EACCES|ENOSYS|ENOENT|EPERM|ENOTTY|ENODEV|EMFILE/i);
            throw new Error(known?.[0] ?? 'terminal-reported-error');
        }
        const input = await until(() => {
            issue();
            if (!terminal.querySelector('[role="status"]')) return firstVisible(terminal, '.xterm textarea');
        }, 'terminal-not-writable', 25000);
        const nonce = 10000 + Math.floor(Math.random() * 50000);
        // The final numeric value does not appear in the command, so echoed input cannot pass.
        const cmd = `test -t 0 && test -t 1 && test -t 2 && printf 'SUGAR_PTY_%d\\n' "$((${nonce}+17))"`;
        const data = new DataTransfer();
        data.setData('text/plain', cmd);
        input.focus();
        input.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
        const enter = {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true};
        input.dispatchEvent(new KeyboardEvent('keydown', enter));
        input.dispatchEvent(new KeyboardEvent('keyup', enter));
        sent = true;
        await until(() => {
            issue();
            return terminal.querySelector('.xterm-rows')?.textContent.includes('SUGAR_PTY_' + (nonce + 17));
        }, 'shell-result-not-rendered');
        read = true;
    } finally {
        if (tabId) {
            const escaped = CSS.escape(tabId);
            const close = panel.querySelector('[data-dockkit-tab-close="' + escaped + '"]');
            if (!close) throw new Error('test-terminal-close-unavailable');
            close.click();
            await until(() => !panel.querySelector('[data-dockkit-tab="' + escaped + '"]'), 'test-terminal-not-closed');
            cleaned = true;
        }
        if (wasClosed && panel.hasAttribute('data-sidebar-right-open')) {
            firstVisible(panel, '[data-sidebar-right-toggle]')?.click();
        }
    }
    return { interactivePty: Boolean(sent && read), testTerminalClosed: cleaned };
}

async function main(port) {
    const list = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(5000) });
    const page = (await list.json()).find(target => target.type === 'page' && /^http:\/\/127\.0\.0\.1:\d+\//.test(target.url));
    if (!page) throw new Error('no-local-dsh-webview');
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    const signal = AbortSignal.timeout(95000);
    try {
        await once(ws, 'open', { signal });
        const reply = once(ws, 'message', { signal });
        ws.send(JSON.stringify({id:1, method:'Runtime.evaluate', params:{
            expression:`(${checkInPage.toString()})().catch(e => ({error:e.message}))`,
            returnByValue:true, awaitPromise:true
        }}));
        const [event] = await reply;
        const result = JSON.parse(event.data).result?.result?.value;
        if (!result?.interactivePty || !result.testTerminalClosed) throw new Error(result?.error ?? 'terminal-check-failed');
        console.log(JSON.stringify(result));
    } finally { ws.close(); }
}

const port = Number(process.argv[2]);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.error('Usage: node check-sidebar-terminal.cjs <forwarded-WebView-port>');
    process.exitCode = 2;
} else main(port).catch(failure => { console.error(failure.message); process.exitCode = 1; });
