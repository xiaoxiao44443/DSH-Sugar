// Node 22+, with the debug app's WebView forwarded to a local TCP port.
// Use on first launch or after upgrading an installation with no workspace yet.
// Observes the upstream UI without changing selection or sending a model request.
const { once } = require('node:events');

async function check(port) {
    const list = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(5000) });
    if (!list.ok) throw Error('Cannot list WebView targets');
    const target = (await list.json()).find(item => item.type === 'page' && /^http:\/\/127\.0\.0\.1:\d+\//.test(item.url));
    if (!target) throw Error('The authenticated DSH WebView is not open');
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    const signal = AbortSignal.timeout(50000);
    try {
        await once(ws, 'open', { signal });
        const expression = `(async () => {
            const until = performance.now() + 45000;
            let pickerFound = false;
            do {
                const picker = document.querySelector('button[aria-label="选择工作区"][aria-haspopup="menu"],button[aria-label="Choose workspace"][aria-haspopup="menu"]');
                pickerFound ||= !!picker;
                if (/无法创建默认工作区|Unable to create default workspace/i.test(document.body.innerText)) return 'default-workspace-error';
                if (picker && /^(默认工作区|Default workspace)$/i.test(picker.textContent.trim())) return 'default-workspace-selected';
                await new Promise(resolve => setTimeout(resolve, 250));
            } while (performance.now() < until);
            return pickerFound ? 'default-workspace-not-selected' : 'workspace-picker-not-rendered';
        })()`;
        const reply = once(ws, 'message', { signal });
        ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }));
        const [event] = await reply;
        const result = JSON.parse(event.data);
        if (result.id !== 1 || result.result?.exceptionDetails || !result.result?.result?.value) {
            throw Error('Cannot inspect the DSH workspace selector');
        }
        const state = result.result.result.value;
        if (state !== 'default-workspace-selected') throw Error(state);
        console.log(state);
    } finally {
        ws.close();
    }
}

const port = Number(process.argv[2]);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.error('Usage: node scripts/check-default-workspace.cjs <forwarded-WebView-port>');
    process.exitCode = 2;
} else {
    check(port).catch(error => { console.error(error.message); process.exitCode = 1; });
}
