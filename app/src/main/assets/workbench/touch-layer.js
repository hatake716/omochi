(() => {
  if (window.__omochiTouchInstalled) return;
  window.__omochiTouchInstalled = true;
  const viewport = document.querySelector('meta[name="viewport"]') || document.createElement('meta');
  viewport.name = 'viewport';
  viewport.content = 'width=device-width, initial-scale=1, maximum-scale=3, viewport-fit=cover';
  if (!viewport.parentNode) document.head.appendChild(viewport);

  // Every current Android System WebView (Chromium 105+) hides unsupported actions with CSS
  // alone. Older WebViews drop the :has() rules and use the bounded menu scan further below.
  const supportsHas = (() => {
    try {
      return !!(window.CSS && CSS.supports && CSS.supports('selector(:has(a))'));
    } catch (error) {
      return false;
    }
  })();

  // Menu rows are identified by the exact aria-label of their .action-label (Code - OSS
  // renders no command ids). Exact matches keep rows such as "拡張機能アカウントの…" and
  // Explorer entries named "extensions" visible.
  const hiddenMenuLabels = [
    '拡張機能',
    'extensions',
    'sign out of code-server',
  ].flatMap((label) => ['', '...', '…'].map((suffix) => `[aria-label="${label}${suffix}" i]`))
    .concat([
      '[aria-label^="その他のデバッガーをインストールします" i]',
      '[aria-label^="install additional debuggers" i]',
    ])
    .join(', ');

  const style = document.createElement('style');
  style.id = 'omochi-touch-style';
  style.textContent = `
    html, body { overscroll-behavior: none; }
    .monaco-workbench { font-family: -apple-system, system-ui, "Noto Sans CJK JP", Roboto, sans-serif !important; }
    .monaco-workbench .part.activitybar .action-item,
    .monaco-workbench .part.activitybar .action-label { min-height: 48px !important; min-width: 48px !important; }
    .monaco-workbench .menubar-menu-button,
    .monaco-workbench .menubar-menu-button .menubar-menu-title {
      min-width: 48px !important;
      min-height: 48px !important;
      line-height: 48px !important;
    }
    .monaco-workbench .part.sidebar .monaco-list-row,
    .monaco-workbench .part.auxiliarybar .monaco-list-row,
    .monaco-workbench .quick-input-list .monaco-list-row { min-height: 40px !important; }
    .monaco-workbench .tabs-container .tab { min-height: 44px !important; }
    .monaco-workbench .tabs-container .tab .tab-label { line-height: 44px !important; }
    .monaco-workbench .monaco-action-bar .action-item > .action-label { min-width: 42px; min-height: 42px; }
    .monaco-workbench .monaco-button { min-height: 44px !important; border-radius: 999px !important; }
    .monaco-workbench .monaco-button-dropdown > .monaco-button.monaco-text-button { border-radius: 999px 0 0 999px !important; }
    .monaco-workbench .monaco-button-dropdown > .monaco-button.monaco-dropdown-button { border-radius: 0 999px 999px 0 !important; }
    .monaco-workbench input, .monaco-workbench textarea { font-size: max(16px, 1em); }
    .monaco-workbench .xterm-helper-textarea { font-size: 16px !important; }
    .monaco-workbench .monaco-inputbox,
    .monaco-workbench .monaco-select-box { border-radius: 10px !important; }
    .monaco-workbench .monaco-scrollable-element > .scrollbar.vertical { width: 16px !important; }
    .monaco-workbench .monaco-scrollable-element > .scrollbar.horizontal { height: 16px !important; }
    .monaco-workbench .monaco-list .monaco-list-row.selected,
    .monaco-workbench .monaco-list .monaco-list-row.focused,
    .monaco-workbench .monaco-list .monaco-list-row:hover { border-radius: 8px; }
    .quick-input-widget {
      top: 6% !important;
      left: 0 !important;
      right: 0 !important;
      width: min(calc(100vw - 24px), 720px) !important;
      margin-left: auto !important;
      margin-right: auto !important;
      border-radius: 14px !important;
      overflow: hidden;
    }
    .context-view, .monaco-menu-container { max-width: calc(100vw - 12px) !important; }
    .monaco-menu .monaco-action-bar.vertical .action-item,
    .monaco-menu .monaco-action-bar.vertical .action-menu-item {
      min-height: 48px !important;
      line-height: 48px !important;
    }
    .monaco-menu .monaco-action-bar.vertical .action-menu-item { border-radius: 8px !important; }
    .monaco-menu .action-label { padding-top: 4px !important; padding-bottom: 4px !important; }
    .menubar-menu-button.open .menubar-menu-items-holder > .monaco-scrollable-element > .scrollbar.vertical {
      opacity: 0 !important;
      pointer-events: none !important;
    }
    .monaco-workbench .part.statusbar { min-height: 32px !important; }
    .monaco-workbench .breadcrumbs-below-tabs { min-height: 38px !important; }

    /* iOS-style surfaces: shape and depth only. Colours stay with the workbench theme, and the
       shadow takes its strength from the theme's widget.shadow (none in high contrast). */
    .monaco-workbench .monaco-dialog-box,
    .monaco-workbench .notifications-toasts .notification-toast,
    .monaco-workbench .notifications-center { border-radius: 14px !important; }
    .monaco-workbench .monaco-menu-container,
    .monaco-workbench .monaco-menu-container > .monaco-scrollable-element,
    .monaco-workbench .monaco-menu,
    .monaco-workbench .monaco-editor .suggest-widget,
    .monaco-workbench .monaco-editor .suggest-widget .monaco-list,
    .monaco-workbench .monaco-hover,
    .monaco-workbench .monaco-resizable-hover { border-radius: 12px !important; }
    .monaco-workbench:not(.hc-black):not(.hc-light) .quick-input-widget,
    .monaco-workbench:not(.hc-black):not(.hc-light) .monaco-menu-container,
    .monaco-workbench:not(.hc-black):not(.hc-light) .monaco-dialog-box,
    .monaco-workbench:not(.hc-black):not(.hc-light) .notifications-toasts .notification-toast,
    .monaco-workbench:not(.hc-black):not(.hc-light) .notifications-center,
    .monaco-workbench:not(.hc-black):not(.hc-light) .monaco-editor .suggest-widget,
    .monaco-workbench:not(.hc-black):not(.hc-light) .monaco-hover {
      box-shadow: 0 12px 32px -8px var(--vscode-widget-shadow), 0 2px 8px -2px var(--vscode-widget-shadow) !important;
    }

    /* Unsupported actions (needs :has(); older WebViews ignore these rules). */
    .monaco-workbench .composite-bar .action-item:has(> .action-label.codicon-extensions-view-icon),
    .monaco-menu .actions-container > .action-item:has(> .action-menu-item > .action-label:is(${hiddenMenuLabels})),
    .monaco-menu .actions-container > .action-item:has(> .action-label.separator):has(
      + .action-item:last-child > .action-menu-item > .action-label:is(${hiddenMenuLabels})) {
      display: none !important;
    }
    @media (max-width: 600px) {
      .monaco-workbench .part.panel { min-height: 200px !important; }
      .monaco-workbench .codicon { font-size: 18px; }
      .monaco-dialog-box {
        box-sizing: border-box !important;
        width: calc(100vw - 16px) !important;
        min-width: 0 !important;
        max-width: calc(100vw - 16px) !important;
        height: min(76vh, 420px) !important;
        min-height: 300px !important;
        max-height: calc(100vh - 24px) !important;
        overflow: hidden !important;
      }
      .monaco-dialog-box .dialog-toolbar-row {
        box-sizing: border-box !important;
        width: 100% !important;
        max-width: 100% !important;
        flex: 0 0 48px !important;
      }
      .monaco-dialog-box .dialog-message-row {
        box-sizing: border-box !important;
        width: 100% !important;
        max-width: 100% !important;
        height: auto !important;
        min-height: 0 !important;
        flex: 1 1 auto !important;
        overflow: hidden !important;
      }
      .monaco-dialog-box .dialog-message-container {
        box-sizing: border-box !important;
        width: calc(100% - 24px) !important;
        min-width: 0 !important;
        max-width: calc(100% - 24px) !important;
        height: 100% !important;
        max-height: 100% !important;
        overflow: auto !important;
      }
      .monaco-dialog-box .dialog-message {
        height: auto !important;
        min-height: 44px !important;
        align-items: flex-start !important;
      }
      .monaco-dialog-box .dialog-buttons-row {
        box-sizing: border-box !important;
        width: 100% !important;
        max-width: 100% !important;
        height: auto !important;
        min-height: 60px !important;
        flex: 0 0 auto !important;
        overflow: visible !important;
      }
      .monaco-dialog-box .dialog-buttons {
        box-sizing: border-box !important;
        width: 100% !important;
        max-width: 100% !important;
        height: auto !important;
        flex-wrap: wrap !important;
        overflow: visible !important;
      }
      .monaco-dialog-box .dialog-buttons .monaco-button { max-width: 100% !important; }
      .monaco-modal-editor-block .modal-editor-resizable {
        left: 8px !important;
        width: calc(100% - 16px) !important;
        min-width: 0 !important;
        max-width: calc(100% - 16px) !important;
      }
    }
  `;
  document.head.appendChild(style);

  const nativeMenuRoutes = new Map([
    ['workbench.action.files.openFolder', 'omochi://menu/open-folder'],
    ['workbench.action.files.openFolderInNewWindow', 'omochi://menu/open-folder'],
    ['workbench.action.files.openFolderViaWorkspace', 'omochi://menu/open-folder'],
    ['workbench.action.files.openLocalFolder', 'omochi://menu/link-device-folder'],
    ['workbench.action.closeFolder', 'omochi://menu/close-folder'],
    ['workbench.action.quit', 'omochi://menu/exit-workbench'],
    ['workbench.action.exit', 'omochi://menu/exit-workbench'],
  ]);
  const nativeMenuLabelRoutes = new Map([
    ['フォルダーを開く', 'omochi://menu/open-folder'],
    ['open folder', 'omochi://menu/open-folder'],
    ['新しいウィンドウでフォルダーを開く', 'omochi://menu/open-folder'],
    ['open folder in new window', 'omochi://menu/open-folder'],
    ['ローカル フォルダーを開く', 'omochi://menu/link-device-folder'],
    ['open local folder', 'omochi://menu/link-device-folder'],
    ['フォルダーを閉じる', 'omochi://menu/close-folder'],
    ['ワークスペースを閉じる', 'omochi://menu/close-folder'],
    ['close folder', 'omochi://menu/close-folder'],
    ['close workspace', 'omochi://menu/close-folder'],
    ['終了', 'omochi://menu/exit-workbench'],
    ['quit', 'omochi://menu/exit-workbench'],
    ['exit', 'omochi://menu/exit-workbench'],
    ['ウィンドウを閉じる', 'omochi://menu/exit-workbench'],
    ['close window', 'omochi://menu/exit-workbench'],
  ]);
  const normalizedMenuLabel = (value) => (value || '')
    .split('\n', 1)[0]
    .trim()
    .replace(/[.…]+$/u, '')
    .trim()
    .toLocaleLowerCase();
  // The label comes from attributes and textContent only: innerText would force a layout.
  const menuLabelOf = (action) => {
    const label = action.querySelector('.action-label');
    return (label && (label.getAttribute('aria-label') || label.textContent)) ||
      action.getAttribute('aria-label') || '';
  };
  const dismissWorkbenchOverlay = () => {
    const target = document.activeElement || document.body;
    const options = {
      key: 'Escape',
      code: 'Escape',
      keyCode: 27,
      which: 27,
      bubbles: true,
      cancelable: true,
      composed: true,
    };
    target.dispatchEvent(new KeyboardEvent('keydown', options));
    target.dispatchEvent(new KeyboardEvent('keyup', options));
    document.querySelector('.menubar-menu-button.open')?.click();
  };
  const menuActionForTarget = (target) => {
    const action = target instanceof Element ? target.closest('.action-menu-item') : null;
    return !action || action.classList.contains('disabled') ? null : action;
  };
  const nativeMenuRouteForAction = (action) => {
    const commandTarget = action.closest('[data-command-id]')
      || action.querySelector('[data-command-id]');
    return nativeMenuRoutes.get(commandTarget?.getAttribute('data-command-id'))
      || nativeMenuLabelRoutes.get(normalizedMenuLabel(menuLabelOf(action)));
  };
  const dispatchNativeMenu = (route) => {
    dismissWorkbenchOverlay();
    window.prompt(
      'omochi-native-menu-v1',
      route.substring('omochi://menu/'.length),
    );
  };
  const stopMenuEvent = (event) => {
    if (event.cancelable) event.preventDefault();
    event.stopImmediatePropagation();
  };

  // Only rows replaced by a native Omochi screen are intercepted. Every other menu row keeps
  // its whole pointer/touch/mouse stream: Code - OSS runs menu items from its own mouseup
  // handler or from the Gesture tap built on document touchstart/touchend, and ignores click.
  let armedMenuAction = null;
  let ignoreMenuEventsUntil = 0;
  let swallowTouchEnd = false;
  const handlePointerDown = (event) => {
    armedMenuAction = null;
    const action = menuActionForTarget(event.target);
    if (!action) return;
    const route = nativeMenuRouteForAction(action);
    if (!route) return;
    armedMenuAction = {
      route,
      x: event.clientX,
      y: event.clientY,
      moved: false,
    };
    // Native replacements must never reach Code - OSS.
    stopMenuEvent(event);
  };
  const handlePointerMove = (event) => {
    const armed = armedMenuAction;
    if (!armed) return;
    if (Math.abs(event.clientX - armed.x) > 12 || Math.abs(event.clientY - armed.y) > 12) {
      armed.moved = true;
    }
    stopMenuEvent(event);
  };
  const handlePointerUp = (event) => {
    const armed = armedMenuAction;
    if (!armed) return;
    armedMenuAction = null;
    ignoreMenuEventsUntil = performance.now() + 600;
    stopMenuEvent(event);
    if (!armed.moved) dispatchNativeMenu(armed.route);
  };
  const handlePointerCancel = () => {
    if (!armedMenuAction) return;
    armedMenuAction = null;
    // Gesture never saw this touch start, so its touchend must not reach it either.
    swallowTouchEnd = true;
  };
  // mousedown / mouseup / touchstart / touchend of an intercepted row.
  const handleCompatibilityEvent = (event) => {
    if (swallowTouchEnd && event.type === 'touchend') {
      swallowTouchEnd = false;
      stopMenuEvent(event);
      return;
    }
    if (armedMenuAction) {
      stopMenuEvent(event);
      return;
    }
    if (performance.now() >= ignoreMenuEventsUntil) return;
    if (menuActionForTarget(event.target)) stopMenuEvent(event);
  };
  const handleClick = (event) => {
    if (performance.now() < ignoreMenuEventsUntil) {
      if (menuActionForTarget(event.target)) stopMenuEvent(event);
      return;
    }
    // A click without our pointer sequence (for example an accessibility action) on a
    // replaced row still opens the native screen. Code - OSS ignores clicks on other rows.
    const action = menuActionForTarget(event.target);
    if (!action) return;
    const route = nativeMenuRouteForAction(action);
    if (!route) return;
    stopMenuEvent(event);
    dispatchNativeMenu(route);
  };
  const captureActive = { capture: true, passive: false };
  window.addEventListener('pointerdown', handlePointerDown, captureActive);
  window.addEventListener('pointermove', handlePointerMove, captureActive);
  window.addEventListener('pointerup', handlePointerUp, captureActive);
  window.addEventListener('pointercancel', handlePointerCancel, { capture: true, passive: true });
  window.addEventListener('mousedown', handleCompatibilityEvent, captureActive);
  window.addEventListener('mouseup', handleCompatibilityEvent, captureActive);
  // touchstart/touchend stay non-passive: an intercepted row must preventDefault them so the
  // WebView does not synthesise mouse events and a click after the native prompt. Passive
  // listeners would not help scrolling either, because Code - OSS already registers
  // non-passive touch listeners on document and sets touch-action: none on the workbench.
  window.addEventListener('touchstart', handleCompatibilityEvent, captureActive);
  window.addEventListener('touchend', handleCompatibilityEvent, captureActive);
  window.addEventListener('click', handleClick, captureActive);

  const unsupportedMenuLabels = new Set([
    '拡張機能',
    'extensions',
    'その他のデバッガーをインストールします',
    'install additional debuggers',
    'sign out of code-server',
  ]);
  const hideElement = (element) => {
    element.style.setProperty('display', 'none', 'important');
    element.setAttribute('aria-hidden', 'true');
  };
  // Fallback for WebViews without :has(): hide the activity bar entry directly, and scan only
  // the rows of open menus, a few times after an interaction that can open a menu.
  const hideUnsupportedActivity = () => {
    const extensions = document.querySelector(
      '.monaco-workbench .composite-bar .action-label.codicon-extensions-view-icon'
    );
    if (!extensions) return false;
    hideElement(extensions.closest('.action-item') || extensions);
    return true;
  };
  const hideUnsupportedMenuRows = () => {
    document.querySelectorAll('.monaco-menu .action-menu-item > .action-label').forEach((label) => {
      const row = label.closest('.action-item');
      if (!row || row.style.display === 'none') return;
      const text = label.getAttribute('aria-label') || label.textContent;
      if (!unsupportedMenuLabels.has(normalizedMenuLabel(text))) return;
      hideElement(row);
      const previous = row.previousElementSibling;
      if (!row.nextElementSibling && previous && previous.querySelector(':scope > .action-label.separator')) {
        hideElement(previous);
      }
    });
  };
  if (!supportsHas) {
    let fallbackPasses = 0;
    let fallbackTimer = 0;
    const runFallbackPass = () => {
      fallbackTimer = 0;
      hideUnsupportedActivity();
      hideUnsupportedMenuRows();
      fallbackPasses -= 1;
      if (fallbackPasses > 0) fallbackTimer = window.setTimeout(runFallbackPass, 250);
    };
    const scheduleFallbackPasses = () => {
      fallbackPasses = 3;
      if (!fallbackTimer) fallbackTimer = window.setTimeout(runFallbackPass, 0);
    };
    const menuKeys = new Set(['Enter', ' ', 'ArrowRight', 'ArrowLeft', 'F10', 'ContextMenu', 'Alt']);
    window.addEventListener('pointerup', scheduleFallbackPasses, { capture: true, passive: true });
    window.addEventListener('contextmenu', scheduleFallbackPasses, { capture: true, passive: true });
    window.addEventListener('keyup', (event) => {
      if (menuKeys.has(event.key)) scheduleFallbackPasses();
    }, { capture: true, passive: true });
    window.addEventListener('mouseover', (event) => {
      if (event.target instanceof Element && event.target.closest('.monaco-submenu-item')) {
        scheduleFallbackPasses();
      }
    }, { capture: true, passive: true });
  }

  // Layout state is read from classes, and the viewport width from a media query, so none of
  // this forces a synchronous layout.
  const compactQuery = window.matchMedia ? window.matchMedia('(max-width: 600px)') : null;
  let compactViewport = compactQuery ? compactQuery.matches : window.innerWidth <= 600;
  if (compactQuery) {
    const updateCompactViewport = (event) => {
      compactViewport = event.matches;
    };
    if (compactQuery.addEventListener) compactQuery.addEventListener('change', updateCompactViewport);
    else if (compactQuery.addListener) compactQuery.addListener(updateCompactViewport);
  }

  let sidebarPrepared = false;
  let auxiliaryBarPrepared = false;
  const prepareCompactLayout = () => {
    if (window.__omochiCompactLayoutPrepared) return true;
    if (!compactViewport) {
      window.__omochiCompactLayoutPrepared = true;
      return true;
    }
    const workbench = document.querySelector('.monaco-workbench');
    if (!workbench || !workbench.querySelector('.part.activitybar .action-item')) return false;
    if (!sidebarPrepared) {
      if (workbench.classList.contains('nosidebar')) {
        sidebarPrepared = true;
      } else {
        const activeView = workbench.querySelector('.part.activitybar .action-item.checked');
        if (activeView) {
          activeView.click();
          sidebarPrepared = true;
        }
      }
    }
    if (!auxiliaryBarPrepared) {
      if (workbench.classList.contains('noauxiliarybar')) {
        auxiliaryBarPrepared = true;
      } else {
        const auxiliaryClose = workbench.querySelector(
          '.part.auxiliarybar .action-label.codicon-auxiliarybar-close'
        );
        if (auxiliaryClose) {
          auxiliaryClose.click();
          auxiliaryBarPrepared = true;
        }
      }
    }
    if (!sidebarPrepared || !auxiliaryBarPrepared) return false;
    window.__omochiCompactLayoutPrepared = true;
    return true;
  };

  let pinnedSplitView = null;
  const keepWorkbenchOrigin = (event) => {
    const splitView = event.currentTarget;
    if (compactViewport && splitView.scrollLeft !== 0) splitView.scrollLeft = 0;
  };
  const pinMobileWorkbenchOrigin = () => {
    const activityBar = document.querySelector('.part.activitybar.left');
    const splitView = activityBar?.parentElement?.parentElement;
    if (!splitView?.classList.contains('split-view-container')) return false;
    if (splitView === pinnedSplitView) return true;
    pinnedSplitView = splitView;
    if (!splitView.__omochiOriginPinned) {
      splitView.__omochiOriginPinned = true;
      splitView.addEventListener('scroll', keepWorkbenchOrigin, { passive: true });
    }
    // Every later shift arrives as a scroll event. Check the current offset once, after the
    // next frame has been laid out, so the read does not force a layout.
    window.requestAnimationFrame(() => window.setTimeout(() => {
      if (compactViewport && splitView.scrollLeft !== 0) splitView.scrollLeft = 0;
    }, 0));
    return true;
  };
  // Resizes (the IME, rotation) only need to follow a rebuilt split view.
  window.addEventListener('resize', pinMobileWorkbenchOrigin, { passive: true });

  // The script runs at onPageFinished, before Code - OSS has built the workbench. Wait for the
  // workbench element with a body childList observer (no subtree), then poll a bounded number
  // of times for the parts it creates asynchronously.
  let setupPasses = 0;
  const runSetupPass = () => {
    const compact = prepareCompactLayout();
    const pinned = pinMobileWorkbenchOrigin();
    const hidden = supportsHas || hideUnsupportedActivity();
    setupPasses += 1;
    if ((compact && pinned && hidden) || setupPasses >= 60) return;
    window.setTimeout(runSetupPass, 250);
  };
  const waitForWorkbench = () => {
    if (document.querySelector('.monaco-workbench')) {
      runSetupPass();
      return;
    }
    let waitLimit = 0;
    const workbenchObserver = new MutationObserver(() => {
      if (!document.querySelector('body > .monaco-workbench')) return;
      workbenchObserver.disconnect();
      window.clearTimeout(waitLimit);
      runSetupPass();
    });
    workbenchObserver.observe(document.body, { childList: true });
    waitLimit = window.setTimeout(() => workbenchObserver.disconnect(), 180000);
  };
  if (document.body) waitForWorkbench();
  else document.addEventListener('DOMContentLoaded', waitForWorkbench, { once: true });
})();
