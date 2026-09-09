(() => {
  'use strict';
  if (window.__GPT_WEB_CAPTURE_HOOK__) return;

  const MAX_EVENTS = 30000;
  const MAX_PERFORMANCE = 15000;
  const hook = {
    version: 3,
    startedAt: Date.now(),
    events: [],
    performance: [],
    closedRoots: [],
    droppedEvents: 0,
    droppedPerformance: 0,
    attachShadowPatched: false,
    mutationObserverInstalled: false,
    performanceObserverTypes: [],
    reportingObserverInstalled: false,
    passiveOnly: true
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
        class: String(target.className || '').slice(0, 1000),
        role: target.getAttribute('role') || '',
        testId: target.getAttribute('data-testid') || '',
        name: target.getAttribute('name') || '',
        type: target.getAttribute('type') || '',
        contentEditable: target.getAttribute('contenteditable') || '',
        ariaLabel: scrub(target.getAttribute('aria-label') || '').slice(0, 1000)
      };
    } catch (_) { return null; }
  };

  const nodeInfo = node => {
    try {
      if (!node) return null;
      if (node.nodeType === Node.ELEMENT_NODE) return targetInfo(node);
      return { nodeType: node.nodeType, nodeName: node.nodeName || '' };
    } catch (_) { return null; }
  };

  const record = (type, data = {}) => {
    const event = {
      at: Date.now(),
      perfNow: typeof performance !== 'undefined' ? performance.now() : null,
      type,
      ...data
    };
    if (hook.events.length < MAX_EVENTS) hook.events.push(event);
    else hook.droppedEvents++;
  };

  Object.defineProperty(window, '__GPT_WEB_CAPTURE_HOOK__', {
    value: hook,
    enumerable: false,
    configurable: false,
    writable: false
  });

  // Passive DOM observation only. No native/prototype APIs are replaced.
  try {
    const observer = new MutationObserver(records => {
      let added = 0, removed = 0, attributes = 0, characterData = 0;
      const attributeNames = new Set();
      const samples = [];
      for (const item of records) {
        if (item.type === 'childList') {
          const addCount = item.addedNodes ? item.addedNodes.length : 0;
          const removeCount = item.removedNodes ? item.removedNodes.length : 0;
          added += addCount;
          removed += removeCount;
          if (samples.length < 24) {
            samples.push({
              type:'childList',
              target:nodeInfo(item.target),
              added:[...(item.addedNodes || [])].slice(0, 8).map(nodeInfo),
              removed:[...(item.removedNodes || [])].slice(0, 8).map(nodeInfo),
              addedCount:addCount,
              removedCount:removeCount
            });
          }
        } else if (item.type === 'attributes') {
          attributes++;
          if (item.attributeName) attributeNames.add(item.attributeName);
          if (samples.length < 24) {
            let currentLength = -1;
            try { currentLength = String(item.target.getAttribute(item.attributeName) || '').length; } catch (_) {}
            samples.push({ type:'attributes',target:nodeInfo(item.target),attributeName:item.attributeName || '',newValueLength:currentLength });
          }
        } else if (item.type === 'characterData') {
          characterData++;
          if (samples.length < 24) samples.push({
            type:'characterData',
            parent:nodeInfo(item.target && item.target.parentElement),
            newValueLength:item.target && item.target.data != null ? String(item.target.data).length : -1
          });
        }
      }
      record('mutation', {
        records: records.length,
        added, removed, attributes, characterData,
        attributeNames: [...attributeNames].slice(0, 100),
        samples
      });
    });
    observer.observe(document, { subtree:true,childList:true,attributes:true,characterData:true });
    hook.mutationObserverInstalled = true;
  } catch (error) {
    record('hookError', { stage:'mutationObserver',message:scrub(error && (error.stack || error.message || error)) });
  }

  const simpleEvents = [
    'readystatechange','DOMContentLoaded','load','pageshow','pagehide','visibilitychange',
    'focus','blur','popstate','hashchange','online','offline','freeze','resume','fullscreenchange'
  ];
  for (const type of simpleEvents) {
    const target = ['readystatechange','visibilitychange','fullscreenchange','freeze','resume'].includes(type) ? document : window;
    target.addEventListener(type, event => record(type, {
      visibilityState: document.visibilityState,
      readyState: document.readyState,
      hasFocus: document.hasFocus(),
      persisted: !!event.persisted,
      url: location.href
    }), { capture:true, passive:true });
  }

  for (const type of ['pointerdown','pointerup','click','focusin','focusout','beforeinput','input','change','submit','invalid','selectionchange']) {
    document.addEventListener(type, event => {
      const data = { target:targetInfo(event.target),trusted:!!event.isTrusted,defaultPrevented:!!event.defaultPrevented };
      if ('inputType' in event) data.inputType = scrub(event.inputType || '');
      if (type === 'selectionchange') {
        try {
          const selection = window.getSelection();
          data.selection = selection ? {
            rangeCount:selection.rangeCount,
            isCollapsed:selection.isCollapsed,
            anchorOffset:selection.anchorOffset,
            focusOffset:selection.focusOffset
          } : null;
        } catch (_) {}
      }
      record(type, data);
    }, { capture:true, passive:true });
  }

  document.addEventListener('formdata', event => {
    const keys = [];
    try { for (const key of event.formData.keys()) keys.push(String(key)); } catch (_) {}
    record('formdata', { target:targetInfo(event.target),keys:[...new Set(keys)].slice(0,200),valueBodiesCaptured:false });
  }, { capture:true, passive:true });

  document.addEventListener('keydown', event => {
    const key = typeof event.key === 'string' && event.key.length === 1 ? '[printable]' : scrub(event.key || '');
    record('keydown', {
      target:targetInfo(event.target),key,code:scrub(event.code || ''),
      altKey:!!event.altKey,ctrlKey:!!event.ctrlKey,metaKey:!!event.metaKey,shiftKey:!!event.shiftKey,
      repeat:!!event.repeat,composing:!!event.isComposing,trusted:!!event.isTrusted,defaultPrevented:!!event.defaultPrevented
    });
  }, { capture:true, passive:true });

  window.addEventListener('resize', () => record('resize', { innerWidth,innerHeight,devicePixelRatio }), { passive:true });
  window.addEventListener('scroll', () => record('scroll', { x:scrollX,y:scrollY }), { capture:true,passive:true });
  if (window.visualViewport) {
    visualViewport.addEventListener('resize', () => record('visualViewport.resize', {
      width:visualViewport.width,height:visualViewport.height,scale:visualViewport.scale,
      offsetLeft:visualViewport.offsetLeft,offsetTop:visualViewport.offsetTop
    }), { passive:true });
    visualViewport.addEventListener('scroll', () => record('visualViewport.scroll', {
      pageLeft:visualViewport.pageLeft,pageTop:visualViewport.pageTop,
      offsetLeft:visualViewport.offsetLeft,offsetTop:visualViewport.offsetTop
    }), { passive:true });
  }

  window.addEventListener('storage', event => record('storage', {
    key:scrub(event.key || ''),
    oldValueLength:event.oldValue == null ? -1 : String(event.oldValue).length,
    newValueLength:event.newValue == null ? -1 : String(event.newValue).length,
    url:scrub(event.url || '')
  }), { passive:true });

  window.addEventListener('error', event => record('windowError', {
    message:scrub(event.message || ''),filename:scrub(event.filename || ''),
    lineno:event.lineno || 0,colno:event.colno || 0
  }), true);

  window.addEventListener('unhandledrejection', event => {
    let reason = '';
    try { reason = String(event.reason && (event.reason.stack || event.reason.message || event.reason)); } catch (_) {}
    record('unhandledRejection', { reason:scrub(reason).slice(0,8000) });
  }, true);

  document.addEventListener('securitypolicyviolation', event => record('securitypolicyviolation', {
    blockedURI:scrub(event.blockedURI || ''),violatedDirective:scrub(event.violatedDirective || ''),
    effectiveDirective:scrub(event.effectiveDirective || ''),sourceFile:scrub(event.sourceFile || ''),
    lineNumber:event.lineNumber || 0,columnNumber:event.columnNumber || 0,statusCode:event.statusCode || 0,
    disposition:scrub(event.disposition || '')
  }), true);

  try {
    if (window.navigation && navigation.addEventListener) {
      navigation.addEventListener('navigate', event => record('navigation.navigate', {
        navigationType:scrub(event.navigationType || ''),canIntercept:!!event.canIntercept,
        hashChange:!!event.hashChange,downloadRequest:scrub(event.downloadRequest || ''),
        destination:event.destination ? {
          url:scrub(event.destination.url || ''),key:scrub(event.destination.key || ''),
          id:scrub(event.destination.id || ''),index:event.destination.index,sameDocument:!!event.destination.sameDocument
        } : null
      }));
      navigation.addEventListener('navigatesuccess', () => record('navigation.navigatesuccess', { url:location.href }));
      navigation.addEventListener('navigateerror', event => record('navigation.navigateerror', { message:scrub(event.message || '') }));
    }
  } catch (error) {
    record('hookError', { stage:'navigationApi',message:scrub(error && (error.stack || error.message || error)) });
  }

  try {
    const supported = PerformanceObserver.supportedEntryTypes || [];
    const wanted = ['longtask','layout-shift','largest-contentful-paint','paint','event','navigation','resource','mark','measure'];
    for (const type of wanted) {
      if (!supported.includes(type)) continue;
      try {
        const observer = new PerformanceObserver(list => {
          for (const entry of list.getEntries()) {
            if (hook.performance.length < MAX_PERFORMANCE) {
              let data;
              try { data = typeof entry.toJSON === 'function' ? entry.toJSON() : { name:entry.name,entryType:entry.entryType,startTime:entry.startTime,duration:entry.duration }; }
              catch (_) { data = { name:entry.name,entryType:entry.entryType,startTime:entry.startTime,duration:entry.duration }; }
              hook.performance.push(data);
            } else hook.droppedPerformance++;
          }
        });
        observer.observe({ type,buffered:true });
        hook.performanceObserverTypes.push(type);
      } catch (_) {}
    }
  } catch (error) {
    record('hookError', { stage:'performanceObserver',message:scrub(error && (error.stack || error.message || error)) });
  }

  try {
    if (typeof ReportingObserver === 'function') {
      const reporting = new ReportingObserver(reports => {
        for (const report of reports) record('browserReport', {
          reportType:scrub(report.type || ''),url:scrub(report.url || '')
        });
      }, { buffered:true });
      reporting.observe();
      hook.reportingObserverInstalled = true;
    }
  } catch (error) {
    record('hookError', { stage:'reportingObserver',message:scrub(error && (error.stack || error.message || error)) });
  }

  record('hookInstalled', {
    readyState:document.readyState,visibilityState:document.visibilityState,hasFocus:document.hasFocus(),url:location.href,
    passiveOnly:true,attachShadowPatched:false,mutationObserverInstalled:hook.mutationObserverInstalled,
    performanceObserverTypes:hook.performanceObserverTypes,reportingObserverInstalled:hook.reportingObserverInstalled
  });
})();
