(() => {
  'use strict';
  if (window.__GPT_WEB_CAPTURE_HOOK__) return;

  const MAX_EVENTS = 20000;
  const MAX_PERFORMANCE = 10000;
  const hook = {
    version: 1,
    startedAt: Date.now(),
    events: [],
    performance: [],
    closedRoots: [],
    droppedEvents: 0,
    droppedPerformance: 0,
    attachShadowPatched: false,
    mutationObserverInstalled: false,
    performanceObserverTypes: []
  };

  const scrub = value => String(value ?? '')
    .replace(/Bearer\s+[^\s,;]{8,}/gi, '[REDACTED_BEARER]')
    .replace(/\b(?:sk|sess|key)-[A-Za-z0-9_-]{16,}\b/gi, '[REDACTED_KEY]')
    .replace(/\b[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/g, '[REDACTED_JWT]');

  const targetInfo = target => {
    try {
      if (!target || target.nodeType !== Node.ELEMENT_NODE) return null;
      return {
        tag: target.tagName,
        id: target.id || '',
        class: String(target.className || '').slice(0, 500),
        role: target.getAttribute('role') || '',
        testId: target.getAttribute('data-testid') || '',
        name: target.getAttribute('name') || '',
        type: target.getAttribute('type') || '',
        contentEditable: target.getAttribute('contenteditable') || '',
        ariaLabel: scrub(target.getAttribute('aria-label') || '').slice(0, 500)
      };
    } catch (_) {
      return null;
    }
  };

  const record = (type, data = {}) => {
    const event = { at: Date.now(), type, ...data };
    if (hook.events.length < MAX_EVENTS) hook.events.push(event);
    else hook.droppedEvents++;
  };

  Object.defineProperty(window, '__GPT_WEB_CAPTURE_HOOK__', {
    value: hook,
    enumerable: false,
    configurable: false,
    writable: false
  });

  try {
    const originalAttachShadow = Element.prototype.attachShadow;
    Element.prototype.attachShadow = function(init) {
      const root = originalAttachShadow.call(this, init);
      try {
        const mode = init && init.mode ? String(init.mode) : '';
        if (mode === 'closed') hook.closedRoots.push({ host: this, root, createdAt: Date.now() });
        record('attachShadow', { mode, host: targetInfo(this) });
      } catch (_) {}
      return root;
    };
    hook.attachShadowPatched = true;
  } catch (error) {
    record('hookError', { stage: 'attachShadow', message: scrub(error && (error.stack || error.message || error)) });
  }

  try {
    const observer = new MutationObserver(records => {
      let added = 0, removed = 0, attributes = 0, characterData = 0;
      const attributeNames = new Set();
      for (const item of records) {
        if (item.type === 'childList') {
          added += item.addedNodes ? item.addedNodes.length : 0;
          removed += item.removedNodes ? item.removedNodes.length : 0;
        } else if (item.type === 'attributes') {
          attributes++;
          if (item.attributeName) attributeNames.add(item.attributeName);
        } else if (item.type === 'characterData') characterData++;
      }
      record('mutation', {
        records: records.length,
        added,
        removed,
        attributes,
        characterData,
        attributeNames: [...attributeNames].slice(0, 50)
      });
    });
    observer.observe(document, { subtree: true, childList: true, attributes: true, characterData: true });
    hook.mutationObserverInstalled = true;
  } catch (error) {
    record('hookError', { stage: 'mutationObserver', message: scrub(error && (error.stack || error.message || error)) });
  }

  const simpleEvents = ['DOMContentLoaded', 'load', 'pageshow', 'pagehide', 'visibilitychange', 'focus', 'blur', 'popstate', 'hashchange', 'online', 'offline'];
  for (const type of simpleEvents) {
    window.addEventListener(type, event => {
      record(type, {
        visibilityState: document.visibilityState,
        hasFocus: document.hasFocus(),
        persisted: !!event.persisted,
        url: location.href
      });
    }, true);
  }

  for (const type of ['pointerdown', 'click', 'focusin', 'focusout', 'input', 'change']) {
    document.addEventListener(type, event => {
      record(type, { target: targetInfo(event.target), trusted: !!event.isTrusted });
    }, true);
  }

  document.addEventListener('keydown', event => {
    const key = typeof event.key === 'string' && event.key.length === 1 ? '[printable]' : scrub(event.key || '');
    record('keydown', {
      target: targetInfo(event.target),
      key,
      code: scrub(event.code || ''),
      altKey: !!event.altKey,
      ctrlKey: !!event.ctrlKey,
      metaKey: !!event.metaKey,
      shiftKey: !!event.shiftKey,
      trusted: !!event.isTrusted
    });
  }, true);

  window.addEventListener('error', event => {
    record('windowError', {
      message: scrub(event.message || ''),
      filename: scrub(event.filename || ''),
      lineno: event.lineno || 0,
      colno: event.colno || 0
    });
  }, true);

  window.addEventListener('unhandledrejection', event => {
    let reason = '';
    try { reason = String(event.reason && (event.reason.stack || event.reason.message || event.reason)); } catch (_) {}
    record('unhandledRejection', { reason: scrub(reason).slice(0, 4000) });
  }, true);

  try {
    for (const method of ['pushState', 'replaceState']) {
      const original = history[method];
      history[method] = function(...args) {
        const result = original.apply(this, args);
        record('history.' + method, { url: location.href });
        return result;
      };
    }
  } catch (error) {
    record('hookError', { stage: 'history', message: scrub(error && (error.stack || error.message || error)) });
  }

  try {
    const supported = PerformanceObserver.supportedEntryTypes || [];
    const wanted = ['longtask', 'layout-shift', 'largest-contentful-paint', 'paint', 'event', 'navigation', 'resource'];
    for (const type of wanted) {
      if (!supported.includes(type)) continue;
      try {
        const observer = new PerformanceObserver(list => {
          for (const entry of list.getEntries()) {
            if (hook.performance.length < MAX_PERFORMANCE) {
              let data;
              try { data = typeof entry.toJSON === 'function' ? entry.toJSON() : { name: entry.name, entryType: entry.entryType, startTime: entry.startTime, duration: entry.duration }; }
              catch (_) { data = { name: entry.name, entryType: entry.entryType, startTime: entry.startTime, duration: entry.duration }; }
              hook.performance.push(data);
            } else hook.droppedPerformance++;
          }
        });
        observer.observe({ type, buffered: true });
        hook.performanceObserverTypes.push(type);
      } catch (_) {}
    }
  } catch (error) {
    record('hookError', { stage: 'performanceObserver', message: scrub(error && (error.stack || error.message || error)) });
  }

  record('hookInstalled', {
    readyState: document.readyState,
    visibilityState: document.visibilityState,
    url: location.href,
    attachShadowPatched: hook.attachShadowPatched,
    mutationObserverInstalled: hook.mutationObserverInstalled,
    performanceObserverTypes: hook.performanceObserverTypes
  });
})();
