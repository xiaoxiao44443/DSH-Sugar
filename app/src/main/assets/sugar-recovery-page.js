(() => {
  'use strict';
  const icons = {
    current:'M9 3h6m-5 0v6l-5.7 9.1a1.8 1.8 0 0 0 1.5 2.8h12.4a1.8 1.8 0 0 0 1.5-2.8L14 9V3M8 14c2.3-1.1 4.5 1.1 7.1.4',
    builtin:'M12 2.5 20.5 7.2v9.6L12 21.5l-8.5-4.7V7.2zM3.8 7.3 12 12l8.2-4.7M12 12v9M8 4.8l8.2 4.6',
    restore:'M3.5 10a8.7 8.7 0 1 1 1.4 8.1M3.5 4.8v5.6h5.6M9 13l2.2 2.3 4.7-5',
    reset:'M3.5 7h3.8m5.4 0h7.8m-17 10h8.8m5.4 0h2.8M7.5 7a2.5 2.5 0 1 0 5 0 2.5 2.5 0 1 0-5 0zm5 10a2.5 2.5 0 1 0 5 0 2.5 2.5 0 1 0-5 0z'
  };
  function row(id) {
    const diagnostic = id === 'current' || id === 'builtin';
    return `<button type="button" data-recovery-action="${id}" disabled>
      <span class="notice-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="${icons[id]}"/></svg></span>
      <span><span data-recovery-label="diag_${id}"></span><small data-recovery-label="diag_${id}_note"></small>
        ${diagnostic ? '<small class="recovery-stage" aria-hidden="true" hidden></small>' : ''}</span>
      <i aria-hidden="true">›</i>${diagnostic ? '<span class="recovery-spinner" aria-hidden="true" hidden></span>' : ''}</button>`;
  }
  const controls = `<div class="recovery-controls" translate="no">
    <p class="recovery-intro" data-recovery-label="diag_intro"></p>
    <h2 data-recovery-label="diag_group_diagnose"></h2><div class="links">${row('current')}${row('builtin')}</div>
    <h2 data-recovery-label="diag_group_restore"></h2><div class="links">${row('restore')}${row('reset')}</div>
    <progress aria-hidden="true" hidden></progress><p class="recovery-status" role="status" aria-live="polite"></p></div>`;
  function mount(pane, onAction) {
    const buttons = pane.querySelectorAll('[data-recovery-action]');
    const status = pane.querySelector('.recovery-status'), progress = pane.querySelector('progress');
    let ready = false;
    for (const button of buttons) button.addEventListener('click', () => {
      if (!button.disabled) onAction(button.dataset.recoveryAction);
    });
    return {update({labels, busy = false, message = '', loadingMode = null}) {
      if (labels) {
        for (const label of pane.querySelectorAll('[data-recovery-label]'))
          label.textContent = labels[label.dataset.recoveryLabel] || '';
        ready = Boolean(labels.diag_intro);
      }
      const launching = busy && (loadingMode === 'current' || loadingMode === 'builtin');
      for (const button of buttons) {
        button.disabled = !ready || busy;
        const stage = button.querySelector('.recovery-stage');
        if (!stage) continue;
        const active = launching && button.dataset.recoveryAction === loadingMode;
        button.toggleAttribute('data-loading',active);
        button.setAttribute('aria-busy',String(active));
        button.querySelector('i').hidden = active;
        button.querySelector('.recovery-spinner').hidden = !active;
        button.querySelector('small[data-recovery-label]').hidden = active && Boolean(message);
        stage.hidden = !active || !message;
        const stageText = active ? message : '';
        if (stage.textContent !== stageText) stage.textContent = stageText;
      }
      progress.hidden = launching || !busy || !message;
      status.classList.toggle('recovery-announcement',launching);
      if (status.textContent !== message) status.textContent = message;
    }};
  }
  window.SugarRecoveryPage = {controls, mount};
})();
