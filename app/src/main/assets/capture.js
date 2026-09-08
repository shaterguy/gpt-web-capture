(() => {
  'use strict';

  const VERSION = 1;
  const CHUNK_SIZE = 48 * 1024;
  const MAX_CACHE_REQUESTS = 5000;
  const STYLE_PROPERTIES = [
    'display','visibility','opacity','position','z-index','content-visibility','contain','isolation',
    'overflow','overflow-x','overflow-y','pointer-events','transform','transform-origin','clip-path',
    'width','height','min-width','min-height','max-width','max-height','top','right','bottom','left',
    'margin-top','margin-right','margin-bottom','margin-left','padding-top','padding-right','padding-bottom','padding-left',
    'flex','flex-direction','flex-grow','flex-shrink','flex-basis','grid-template-columns','grid-template-rows',
    'align-items','align-content','justify-content','order','float','clear','box-sizing','white-space',
    'font-family','font-size','font-weight','line-height','color','background-color'
  ];
  const SENSITIVE_NAME = /(token|auth|authorization|cookie|session|password|passwd|secret|api[-_]?key|credential|jwt|access|refresh|csrf|xsrf|code|signature|sig|nonce)/i;
  const URL_ATTRIBUTES = new Set(['href','src','action','formaction','poster','cite','background','data','srcset']);

  const scrubText = input => String(input ?? '')
    .replace(/Bearer\s+[^\s,;]{8,}/gi, '[REDACTED_BEARER]')
    .replace(/\b(?:sk|sess|key)-[A-Za-z0-9_-]{16,}\b/gi, '[REDACTED_KEY]')
    .replace(/\b[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/g, '[REDACTED_JWT]')
    .replace(/(?<![A-Za-z0-9_=-])[A-Za-z0-9_+/=-]{96,}(?![A-Za-z0-9_=-])/g, '[REDACTED_LONG_TOKEN]');

  const isSensitiveName = name => SENSITIVE_NAME.test(String(name || ''));
  const looksLikeSecret = value => {
    const s = String(value || '');
    return s.length > 96 || /\b[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/.test(s) || /\b(?:sk|sess|key)-[A-Za-z0-9_-]{16,}\b/i.test(s);
  };

  const safeUrl = raw => {
    const value = String(raw || '');
    if (!value) return '';
    if (/^data:/i.test(value)) return '[DATA_URL_REDACTED len=' + value.length + ']';
    if (/^blob:/i.test(value)) return value.split('#')[0];
    try {
      const u = new URL(value, location.href);
      for (const [key, val] of [...u.searchParams.entries()]) {
        if (isSensitiveName(key) || looksLikeSecret(val)) u.searchParams.set(key, '[REDACTED len=' + val.length + ']');
        else u.searchParams.set(key, scrubText(val));
      }
      if (u.hash) u.hash = '#[REDACTED_FRAGMENT len=' + u.hash.slice(1).length + ']';
      return u.href;
    } catch (_) {
      return scrubText(value);
    }
  };

  const safeAttributeValue = (name, value) => {
    const n = String(name || '').toLowerCase();
    const v = String(value || '');
    if (n.startsWith('on')) return '[INLINE_HANDLER_REDACTED len=' + v.length + ']';
    if (isSensitiveName(n)) return '[REDACTED len=' + v.length + ']';
    if (URL_ATTRIBUTES.has(n)) return safeUrl(v);
    if (looksLikeSecret(v)) return '[REDACTED len=' + v.length + ']';
    return scrubText(v);
  };

  const bytesToHex = buffer => [...new Uint8Array(buffer)].map(b => b.toString(16).padStart(2, '0')).join('');
  const hashText = async value => {
    const text = String(value ?? '');
    try {
      if (!crypto || !crypto.subtle || typeof TextEncoder === 'undefined') return 'unavailable';
      return bytesToHex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)));
    } catch (_) {
      return 'unavailable';
    }
  };

  const json = value => JSON.stringify(value, (_key, item) => typeof item === 'bigint' ? String(item) : item, 2);

  const send = (bridge, token, path, value) => {
    const text = typeof value === 'string' ? value : json(value);
    const total = Math.max(1, Math.ceil(text.length / CHUNK_SIZE));
    for (let i = 0; i < total; i++) {
      bridge.push(token, path, i, total, text.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE));
    }
    return { path, chars: text.length, chunks: total };
  };

  const rect = element => {
    try {
      const r = element.getBoundingClientRect();
      return { x:r.x, y:r.y, top:r.top, right:r.right, bottom:r.bottom, left:r.left, width:r.width, height:r.height };
    } catch (_) { return null; }
  };

  const elementPath = element => {
    try {
      if (!element || element.nodeType !== Node.ELEMENT_NODE) return '';
      const parts = [];
      let current = element;
      for (let depth = 0; current && depth < 12; depth++) {
        let part = current.tagName.toLowerCase();
        if (current.id) {
          part += '#' + CSS.escape(current.id);
          parts.unshift(part);
          break;
        }
        const classes = [...current.classList].slice(0, 3).map(c => '.' + CSS.escape(c)).join('');
        if (classes) part += classes;
        const parent = current.parentElement;
        if (parent) {
          const siblings = [...parent.children].filter(x => x.tagName === current.tagName);
          if (siblings.length > 1) part += ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')';
        }
        parts.unshift(part);
        const root = current.getRootNode();
        if (root instanceof ShadowRoot && current.parentElement == null) {
          parts.unshift('::shadow(' + elementPath(root.host) + ')');
          current = root.host;
        } else current = parent;
      }
      return parts.join(' > ');
    } catch (_) { return ''; }
  };

  const rootDescriptor = element => {
    try {
      const root = element.getRootNode();
      if (root instanceof ShadowRoot) return { type:'ShadowRoot', mode:root.mode, host:elementPath(root.host) };
      if (root instanceof Document) return { type:'Document' };
      return { type:root && root.constructor ? root.constructor.name : typeof root };
    } catch (_) { return null; }
  };

  const descriptor = element => {
    if (!element || element.nodeType !== Node.ELEMENT_NODE) return null;
    const attrs = {};
    try {
      for (const attr of [...element.attributes]) attrs[attr.name] = safeAttributeValue(attr.name, attr.value);
    } catch (_) {}
    const result = {
      tag: element.tagName,
      path: elementPath(element),
      id: element.id || '',
      className: String(element.className || ''),
      role: element.getAttribute('role') || '',
      testId: element.getAttribute('data-testid') || '',
      name: element.getAttribute('name') || '',
      type: element.getAttribute('type') || '',
      placeholder: scrubText(element.getAttribute('placeholder') || ''),
      ariaLabel: scrubText(element.getAttribute('aria-label') || ''),
      contentEditable: element.getAttribute('contenteditable') || '',
      tabIndex: element.tabIndex,
      hidden: !!element.hidden,
      inert: !!element.inert,
      disabled: !!element.disabled,
      readOnly: !!element.readOnly,
      isConnected: !!element.isConnected,
      rect: rect(element),
      clientRects: (() => { try { return element.getClientRects().length; } catch (_) { return -1; } })(),
      offsetWidth: element.offsetWidth,
      offsetHeight: element.offsetHeight,
      clientWidth: element.clientWidth,
      clientHeight: element.clientHeight,
      scrollWidth: element.scrollWidth,
      scrollHeight: element.scrollHeight,
      textLength: String(element.innerText || element.textContent || '').length,
      attributes: attrs,
      root: rootDescriptor(element)
    };
    try {
      result.offsetParent = element.offsetParent ? { tag:element.offsetParent.tagName, path:elementPath(element.offsetParent) } : null;
    } catch (_) { result.offsetParent = null; }
    try {
      result.matchesFocus = element.matches(':focus');
      result.matchesFocusVisible = element.matches(':focus-visible');
    } catch (_) {}
    try {
      result.checkVisibility = typeof element.checkVisibility === 'function' ? element.checkVisibility({ checkOpacity:true, checkVisibilityCSS:true }) : null;
    } catch (_) { result.checkVisibility = null; }
    return result;
  };

  const computedStyle = element => {
    const out = {};
    try {
      const style = getComputedStyle(element);
      for (const name of STYLE_PROPERTIES) out[name] = style.getPropertyValue(name);
    } catch (error) { out.error = scrubText(error && (error.message || error)); }
    return out;
  };

  const sanitizeTree = root => {
    const elements = [];
    if (root && root.nodeType === Node.ELEMENT_NODE) elements.push(root);
    if (root && typeof root.querySelectorAll === 'function') elements.push(...root.querySelectorAll('*'));
    for (const element of elements) {
      try {
        if (element.tagName === 'SCRIPT') {
          const length = String(element.textContent || '').length;
          element.textContent = '[INLINE_SCRIPT_REDACTED len=' + length + ']';
        }
        if (element.tagName === 'TEXTAREA') {
          const length = String(element.textContent || '').length;
          element.textContent = length ? '[TEXTAREA_VALUE_REDACTED len=' + length + ']' : '';
        }
        if (element.tagName === 'INPUT') {
          const length = String(element.getAttribute('value') || '').length;
          if (element.hasAttribute('value')) element.setAttribute('data-capture-value-length', String(length));
          element.removeAttribute('value');
        }
        for (const attr of [...element.attributes]) {
          const next = safeAttributeValue(attr.name, attr.value);
          if (next !== attr.value) element.setAttribute(attr.name, next);
        }
        if (element.tagName === 'IFRAME' && element.hasAttribute('srcdoc')) {
          const length = String(element.getAttribute('srcdoc') || '').length;
          element.setAttribute('srcdoc', '[SRCDOC_REDACTED len=' + length + ']');
        }
        if (element.tagName === 'META') {
          const name = element.getAttribute('name') || element.getAttribute('http-equiv') || '';
          if (isSensitiveName(name) && element.hasAttribute('content')) {
            const length = String(element.getAttribute('content') || '').length;
            element.setAttribute('content', '[REDACTED len=' + length + ']');
          }
        }
      } catch (_) {}
    }
    return root;
  };

  const sanitizedDocumentHtml = doc => {
    try {
      const clone = doc.documentElement.cloneNode(true);
      sanitizeTree(clone);
      const doctype = doc.doctype ? '<!DOCTYPE ' + doc.doctype.name + '>\n' : '';
      return doctype + clone.outerHTML;
    } catch (error) {
      return '<!-- capture failed: ' + scrubText(error && (error.stack || error)) + ' -->';
    }
  };

  const sanitizedFragmentHtml = (root, ownerDocument) => {
    try {
      const container = ownerDocument.createElement('div');
      for (const child of [...root.childNodes]) container.appendChild(child.cloneNode(true));
      sanitizeTree(container);
      return container.innerHTML;
    } catch (error) {
      return '<!-- shadow capture failed: ' + scrubText(error && (error.stack || error)) + ' -->';
    }
  };

  const inventory = root => {
    const elements = [];
    if (root instanceof Document) elements.push(...root.querySelectorAll('*'));
    else if (root && typeof root.querySelectorAll === 'function') elements.push(...root.querySelectorAll('*'));
    return elements.map((element, index) => ({ index, ...descriptor(element), computedStyle: computedStyle(element) }));
  };

  const semanticInventory = root => {
    const selector = 'input,textarea,select,button,a[href],[contenteditable],[role],[aria-label],[aria-labelledby],[tabindex],form';
    const elements = root && typeof root.querySelectorAll === 'function' ? [...root.querySelectorAll(selector)] : [];
    return elements.map(element => {
      const base = descriptor(element);
      const labels = [];
      try {
        if (element.labels) for (const label of [...element.labels]) labels.push(scrubText(label.innerText || label.textContent || ''));
      } catch (_) {}
      return { ...base, labels, computedStyle: computedStyle(element) };
    });
  };

  const parentChain = element => {
    const out = [];
    let current = element;
    for (let i = 0; current && i < 10; i++) {
      out.push(descriptor(current));
      const root = current.getRootNode();
      current = current.parentElement || (root instanceof ShadowRoot ? root.host : null);
    }
    return out;
  };

  const composerCandidates = () => {
    const selectors = [
      'textarea#prompt-textarea',
      'textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]',
      '[contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]',
      '[role="textbox"]',
      'textarea',
      '[contenteditable]',
      '[data-testid*="prompt"]',
      '[data-testid*="composer"]',
      'form'
    ];
    const bySelector = {};
    for (const selector of selectors) {
      const matches = [...document.querySelectorAll(selector)];
      bySelector[selector] = matches.map(element => ({
        ...descriptor(element),
        computedStyle: computedStyle(element),
        parentChain: parentChain(element),
        activeElement: document.activeElement === element,
        closestForm: element.closest ? descriptor(element.closest('form')) : null
      }));
    }
    return {
      url: safeUrl(location.href),
      readyState: document.readyState,
      visibilityState: document.visibilityState,
      hasFocus: document.hasFocus(),
      formsLength: document.forms.length,
      activeElement: descriptor(document.activeElement),
      selectors: bySelector
    };
  };

  const scrubObject = (value, keyName = '') => {
    if (value == null) return value;
    if (typeof value === 'string') {
      if (isSensitiveName(keyName)) return '[REDACTED len=' + value.length + ']';
      if (/^(?:https?:|blob:|data:)/i.test(value)) return safeUrl(value);
      return looksLikeSecret(value) ? '[REDACTED len=' + value.length + ']' : scrubText(value);
    }
    if (Array.isArray(value)) return value.map(item => scrubObject(item, keyName));
    if (typeof value === 'object') {
      const out = {};
      for (const [key, item] of Object.entries(value)) out[key] = scrubObject(item, key);
      return out;
    }
    return value;
  };

  const collectShadowRoots = () => {
    const roots = [];
    const seen = new Set();
    const visit = root => {
      const elements = root && typeof root.querySelectorAll === 'function' ? [...root.querySelectorAll('*')] : [];
      for (const element of elements) {
        try {
          if (element.shadowRoot && !seen.has(element.shadowRoot)) {
            seen.add(element.shadowRoot);
            roots.push({ root:element.shadowRoot, host:element, mode:element.shadowRoot.mode || 'open', source:'live-open' });
            visit(element.shadowRoot);
          }
        } catch (_) {}
      }
    };
    visit(document);
    const hook = window.__GPT_WEB_CAPTURE_HOOK__;
    if (hook && Array.isArray(hook.closedRoots)) {
      for (const item of hook.closedRoots) {
        try {
          if (item && item.root && !seen.has(item.root)) {
            seen.add(item.root);
            roots.push({ root:item.root, host:item.host, mode:'closed', source:'document-start-hook', createdAt:item.createdAt || 0 });
            visit(item.root);
          }
        } catch (_) {}
      }
    }
    return roots;
  };

  const captureFrames = async (bridge, token, sent) => {
    const index = [];
    const frames = [...document.querySelectorAll('iframe,frame')];
    for (let i = 0; i < frames.length; i++) {
      const frame = frames[i];
      const row = {
        index:i,
        element:descriptor(frame),
        src:safeUrl(frame.getAttribute('src') || ''),
        name:frame.getAttribute('name') || '',
        sameOrigin:false
      };
      try {
        const doc = frame.contentDocument;
        if (doc && doc.documentElement) {
          row.sameOrigin = true;
          row.url = safeUrl(doc.location && doc.location.href || '');
          row.title = scrubText(doc.title || '');
          row.readyState = doc.readyState;
          sent.push(send(bridge, token, 'frames/frame-' + String(i).padStart(3, '0') + '.html', sanitizedDocumentHtml(doc)));
          sent.push(send(bridge, token, 'frames/frame-' + String(i).padStart(3, '0') + '-elements.json', inventory(doc)));
        }
      } catch (error) {
        row.error = scrubText(error && (error.name + ': ' + error.message));
      }
      index.push(row);
    }
    sent.push(send(bridge, token, 'frames/index.json', index));
  };

  const captureShadows = async (bridge, token, sent) => {
    const roots = collectShadowRoots();
    const index = [];
    for (let i = 0; i < roots.length; i++) {
      const item = roots[i];
      const prefix = 'shadow/root-' + String(i).padStart(3, '0');
      index.push({
        index:i,
        mode:item.mode,
        source:item.source,
        createdAt:item.createdAt || 0,
        host:descriptor(item.host)
      });
      sent.push(send(bridge, token, prefix + '.html', sanitizedFragmentHtml(item.root, item.host.ownerDocument || document)));
      sent.push(send(bridge, token, prefix + '-elements.json', inventory(item.root)));
    }
    sent.push(send(bridge, token, 'shadow/index.json', index));
  };

  const stylesheets = () => [...document.styleSheets].map((sheet, index) => {
    const row = {
      index,
      href:safeUrl(sheet.href || ''),
      disabled:!!sheet.disabled,
      media:sheet.media ? [...sheet.media] : [],
      owner:sheet.ownerNode ? descriptor(sheet.ownerNode) : null
    };
    try {
      row.rules = [...sheet.cssRules].map(rule => scrubText(rule.cssText));
      row.ruleCount = row.rules.length;
    } catch (error) {
      row.rules = null;
      row.error = scrubText(error && (error.name + ': ' + error.message));
    }
    return row;
  });

  const browserEnvironment = async () => {
    const nav = navigator;
    const environment = {
      url:safeUrl(location.href),
      origin:location.origin,
      title:scrubText(document.title || ''),
      readyState:document.readyState,
      visibilityState:document.visibilityState,
      hasFocus:document.hasFocus(),
      prerendering:!!document.prerendering,
      compatMode:document.compatMode,
      characterSet:document.characterSet,
      contentType:document.contentType,
      designMode:document.designMode,
      devicePixelRatio:window.devicePixelRatio,
      innerWidth:window.innerWidth,
      innerHeight:window.innerHeight,
      outerWidth:window.outerWidth,
      outerHeight:window.outerHeight,
      scrollX:window.scrollX,
      scrollY:window.scrollY,
      historyLength:history.length,
      screen:{ width:screen.width,height:screen.height,availWidth:screen.availWidth,availHeight:screen.availHeight,colorDepth:screen.colorDepth,pixelDepth:screen.pixelDepth,orientation:screen.orientation ? {type:screen.orientation.type,angle:screen.orientation.angle} : null },
      visualViewport:window.visualViewport ? { offsetLeft:visualViewport.offsetLeft,offsetTop:visualViewport.offsetTop,pageLeft:visualViewport.pageLeft,pageTop:visualViewport.pageTop,width:visualViewport.width,height:visualViewport.height,scale:visualViewport.scale } : null,
      navigator:{
        userAgent:nav.userAgent,
        appVersion:nav.appVersion,
        platform:nav.platform,
        vendor:nav.vendor,
        language:nav.language,
        languages:nav.languages,
        hardwareConcurrency:nav.hardwareConcurrency,
        deviceMemory:nav.deviceMemory,
        maxTouchPoints:nav.maxTouchPoints,
        cookieEnabled:nav.cookieEnabled,
        onLine:nav.onLine,
        webdriver:nav.webdriver,
        pdfViewerEnabled:nav.pdfViewerEnabled
      },
      connection:nav.connection ? scrubObject({ effectiveType:nav.connection.effectiveType,downlink:nav.connection.downlink,rtt:nav.connection.rtt,saveData:nav.connection.saveData,type:nav.connection.type }) : null,
      mediaQueries:{
        dark:matchMedia('(prefers-color-scheme: dark)').matches,
        light:matchMedia('(prefers-color-scheme: light)').matches,
        reducedMotion:matchMedia('(prefers-reduced-motion: reduce)').matches,
        coarsePointer:matchMedia('(pointer: coarse)').matches,
        finePointer:matchMedia('(pointer: fine)').matches,
        hover:matchMedia('(hover: hover)').matches,
        standalone:matchMedia('(display-mode: standalone)').matches
      },
      featurePolicy:document.featurePolicy && typeof document.featurePolicy.allowedFeatures === 'function' ? document.featurePolicy.allowedFeatures() : null,
      features:{
        checkVisibility:'checkVisibility' in Element.prototype,
        shadowDom:'attachShadow' in Element.prototype,
        adoptedStyleSheets:'adoptedStyleSheets' in Document.prototype,
        viewTransitions:'startViewTransition' in Document.prototype,
        navigationApi:'navigation' in window,
        scheduler:'scheduler' in window,
        webGpu:'gpu' in navigator,
        serviceWorker:'serviceWorker' in navigator,
        indexedDB:'indexedDB' in window,
        cacheStorage:'caches' in window,
        compressionStream:'CompressionStream' in window,
        performanceObserver:'PerformanceObserver' in window
      }
    };
    try {
      if (nav.userAgentData) {
        environment.userAgentData = { brands:nav.userAgentData.brands,mobile:nav.userAgentData.mobile,platform:nav.userAgentData.platform };
        if (nav.userAgentData.getHighEntropyValues) {
          environment.userAgentHighEntropy = await nav.userAgentData.getHighEntropyValues(['architecture','bitness','formFactors','fullVersionList','model','platformVersion','uaFullVersion','wow64']);
        }
      }
    } catch (error) { environment.userAgentDataError = scrubText(error && (error.message || error)); }
    return environment;
  };

  const performanceSnapshot = () => {
    const entries = performance.getEntries().map(entry => {
      try { return scrubObject(typeof entry.toJSON === 'function' ? entry.toJSON() : { name:entry.name,entryType:entry.entryType,startTime:entry.startTime,duration:entry.duration }); }
      catch (_) { return { name:safeUrl(entry.name || ''),entryType:entry.entryType,startTime:entry.startTime,duration:entry.duration }; }
    });
    const out = { timeOrigin:performance.timeOrigin, now:performance.now(), entries };
    try {
      if (performance.memory) out.memory = { jsHeapSizeLimit:performance.memory.jsHeapSizeLimit,totalJSHeapSize:performance.memory.totalJSHeapSize,usedJSHeapSize:performance.memory.usedJSHeapSize };
    } catch (_) {}
    return out;
  };

  const storageSnapshot = async () => {
    const storage = async source => {
      const out = [];
      try {
        for (let i = 0; i < source.length; i++) {
          const key = source.key(i);
          const value = source.getItem(key) || '';
          out.push({ key:scrubText(key), valueLength:value.length, valueSha256:await hashText(value) });
        }
      } catch (error) { out.push({ error:scrubText(error && (error.message || error)) }); }
      return out;
    };
    const cookies = [];
    try {
      for (const part of String(document.cookie || '').split(/;\s*/)) {
        if (!part) continue;
        const eq = part.indexOf('=');
        const name = eq >= 0 ? part.slice(0, eq) : part;
        const value = eq >= 0 ? part.slice(eq + 1) : '';
        cookies.push({ name, valueLength:value.length, valueSha256:await hashText(value) });
      }
    } catch (_) {}
    const out = {
      localStorage:await storage(localStorage),
      sessionStorage:await storage(sessionStorage),
      documentCookies:cookies,
      rawValuesPersisted:false
    };
    try {
      if (indexedDB.databases) out.indexedDB = (await indexedDB.databases()).map(db => ({ name:scrubText(db.name || ''),version:db.version }));
      else out.indexedDB = { supported:false };
    } catch (error) { out.indexedDB = { error:scrubText(error && (error.message || error)) }; }
    try {
      if (navigator.storage && navigator.storage.estimate) out.storageEstimate = await navigator.storage.estimate();
      if (navigator.storage && navigator.storage.persisted) out.storagePersisted = await navigator.storage.persisted();
    } catch (error) { out.storageError = scrubText(error && (error.message || error)); }
    return out;
  };

  const serviceWorkers = async () => {
    if (!('serviceWorker' in navigator)) return { supported:false };
    try {
      const registrations = await navigator.serviceWorker.getRegistrations();
      return {
        supported:true,
        controller:navigator.serviceWorker.controller ? { scriptURL:safeUrl(navigator.serviceWorker.controller.scriptURL || ''),state:navigator.serviceWorker.controller.state } : null,
        registrations:registrations.map(registration => ({
          scope:safeUrl(registration.scope || ''),
          updateViaCache:registration.updateViaCache,
          active:registration.active ? { scriptURL:safeUrl(registration.active.scriptURL || ''),state:registration.active.state } : null,
          waiting:registration.waiting ? { scriptURL:safeUrl(registration.waiting.scriptURL || ''),state:registration.waiting.state } : null,
          installing:registration.installing ? { scriptURL:safeUrl(registration.installing.scriptURL || ''),state:registration.installing.state } : null
        }))
      };
    } catch (error) { return { supported:true,error:scrubText(error && (error.message || error)) }; }
  };

  const cacheMetadata = async () => {
    if (!('caches' in window)) return { supported:false };
    const result = { supported:true,caches:[],droppedRequests:0 };
    try {
      for (const name of await caches.keys()) {
        const cache = await caches.open(name);
        const requests = await cache.keys();
        const kept = requests.slice(0, Math.max(0, MAX_CACHE_REQUESTS - result.caches.reduce((sum,c) => sum + c.requests.length, 0)));
        const row = { name:scrubText(name),requests:kept.map(request => ({ url:safeUrl(request.url),method:request.method,mode:request.mode,credentials:request.credentials,cache:request.cache,redirect:request.redirect })) };
        result.caches.push(row);
        result.droppedRequests += requests.length - kept.length;
        if (result.caches.reduce((sum,c) => sum + c.requests.length, 0) >= MAX_CACHE_REQUESTS) break;
      }
    } catch (error) { result.error = scrubText(error && (error.message || error)); }
    return result;
  };

  const permissions = async () => {
    const names = ['geolocation','notifications','camera','microphone','clipboard-read','clipboard-write','midi','persistent-storage'];
    const out = [];
    if (!navigator.permissions || !navigator.permissions.query) return { supported:false,items:out };
    for (const name of names) {
      try {
        const status = await navigator.permissions.query({ name });
        out.push({ name,state:status.state });
      } catch (error) { out.push({ name,error:scrubText(error && (error.name || error.message || error)) }); }
    }
    return { supported:true,items:out };
  };

  const webGl = () => {
    const out = { supported:false };
    try {
      const canvas = document.createElement('canvas');
      const gl = canvas.getContext('webgl2') || canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
      if (!gl) return out;
      out.supported = true;
      out.version = gl.getParameter(gl.VERSION);
      out.shadingLanguageVersion = gl.getParameter(gl.SHADING_LANGUAGE_VERSION);
      out.vendor = gl.getParameter(gl.VENDOR);
      out.renderer = gl.getParameter(gl.RENDERER);
      out.maxTextureSize = gl.getParameter(gl.MAX_TEXTURE_SIZE);
      out.maxViewportDims = [...gl.getParameter(gl.MAX_VIEWPORT_DIMS)];
      const ext = gl.getExtension('WEBGL_debug_renderer_info');
      if (ext) {
        out.unmaskedVendor = gl.getParameter(ext.UNMASKED_VENDOR_WEBGL);
        out.unmaskedRenderer = gl.getParameter(ext.UNMASKED_RENDERER_WEBGL);
      }
      out.extensions = gl.getSupportedExtensions();
    } catch (error) { out.error = scrubText(error && (error.message || error)); }
    return out;
  };

  const scriptResources = async () => {
    const out = [];
    for (const [index, script] of [...document.scripts].entries()) {
      const inline = script.src ? '' : String(script.textContent || '');
      out.push({
        index,
        src:safeUrl(script.src || ''),
        type:script.type || '',
        async:!!script.async,
        defer:!!script.defer,
        noModule:!!script.noModule,
        integrity:script.integrity || '',
        crossOrigin:script.crossOrigin || '',
        inlineLength:inline.length,
        inlineSha256:inline ? await hashText(inline) : ''
      });
    }
    return out;
  };

  const linksAndMeta = () => ({
    links:[...document.querySelectorAll('link')].map(link => ({ rel:link.rel,as:link.as,type:link.type,media:link.media,crossOrigin:link.crossOrigin,integrity:link.integrity,href:safeUrl(link.href || '') })),
    meta:[...document.querySelectorAll('meta')].map(meta => ({ name:meta.name || '',httpEquiv:meta.httpEquiv || '',property:meta.getAttribute('property') || '',content:isSensitiveName(meta.name || meta.httpEquiv || meta.getAttribute('property') || '') ? '[REDACTED len=' + String(meta.content || '').length + ']' : scrubText(meta.content || '') }))
  });

  const frameworkMarkers = () => {
    const markerCounts = {};
    let nodesWithMarkers = 0;
    for (const element of document.querySelectorAll('*')) {
      let found = false;
      for (const key of Object.keys(element)) {
        if (/^__(react|next|vue|svelte|preact)/i.test(key)) {
          markerCounts[key] = (markerCounts[key] || 0) + 1;
          found = true;
        }
      }
      if (found) nodesWithMarkers++;
    }
    const windowKeys = Object.keys(window).filter(key => /react|next|webpack|vite|remix|apollo/i.test(key)).slice(0, 1000);
    return { markerCounts,nodesWithMarkers,windowMarkerKeys:windowKeys };
  };

  const documentSnapshot = () => {
    const selection = window.getSelection();
    const ranges = [];
    if (selection) {
      for (let i = 0; i < selection.rangeCount; i++) {
        const range = selection.getRangeAt(i);
        ranges.push({ collapsed:range.collapsed,startOffset:range.startOffset,endOffset:range.endOffset,startContainerType:range.startContainer && range.startContainer.nodeType,endContainerType:range.endContainer && range.endContainer.nodeType });
      }
    }
    return {
      url:safeUrl(location.href),
      title:scrubText(document.title || ''),
      readyState:document.readyState,
      visibilityState:document.visibilityState,
      hasFocus:document.hasFocus(),
      formsLength:document.forms.length,
      activeElement:descriptor(document.activeElement),
      scrollingElement:descriptor(document.scrollingElement),
      fullscreenElement:descriptor(document.fullscreenElement),
      pictureInPictureElement:descriptor(document.pictureInPictureElement),
      selection:{ anchorOffset:selection ? selection.anchorOffset : 0,focusOffset:selection ? selection.focusOffset : 0,isCollapsed:selection ? selection.isCollapsed : true,rangeCount:selection ? selection.rangeCount : 0,ranges }
    };
  };

  const formsSnapshot = () => [...document.forms].map((form, index) => ({
    index,
    element:descriptor(form),
    action:safeUrl(form.action || ''),
    method:form.method || '',
    enctype:form.enctype || '',
    target:form.target || '',
    noValidate:!!form.noValidate,
    controls:[...form.elements].map(control => descriptor(control))
  }));

  const hookSnapshot = () => {
    const hook = window.__GPT_WEB_CAPTURE_HOOK__;
    if (!hook) return { installed:false };
    return {
      installed:true,
      version:hook.version,
      startedAt:hook.startedAt,
      droppedEvents:hook.droppedEvents,
      droppedPerformance:hook.droppedPerformance,
      attachShadowPatched:hook.attachShadowPatched,
      mutationObserverInstalled:hook.mutationObserverInstalled,
      performanceObserverTypes:hook.performanceObserverTypes,
      events:scrubObject(hook.events || []),
      performance:scrubObject(hook.performance || []),
      closedRootCount:Array.isArray(hook.closedRoots) ? hook.closedRoots.length : 0
    };
  };

  const run = async (bridgeName, token) => {
    const bridge = window[bridgeName];
    if (!bridge || typeof bridge.push !== 'function') throw new Error('native capture bridge unavailable');
    const sent = [];
    const failures = [];
    const stage = async (name, fn) => {
      try { await fn(); }
      catch (error) { failures.push({ stage:name,error:scrubText(error && (error.stack || error.message || error)) }); }
    };

    await stage('document', async () => {
      sent.push(send(bridge, token, 'dom/sanitized.html', sanitizedDocumentHtml(document)));
      sent.push(send(bridge, token, 'dom/document.json', documentSnapshot()));
      sent.push(send(bridge, token, 'dom/elements.json', inventory(document)));
      sent.push(send(bridge, token, 'dom/accessibility-semantics.json', semanticInventory(document)));
      sent.push(send(bridge, token, 'dom/forms.json', formsSnapshot()));
      sent.push(send(bridge, token, 'dom/composer-candidates.json', composerCandidates()));
    });

    await stage('shadow', () => captureShadows(bridge, token, sent));
    await stage('frames', () => captureFrames(bridge, token, sent));
    await stage('css', async () => sent.push(send(bridge, token, 'css/stylesheets.json', stylesheets())));
    await stage('performance', async () => sent.push(send(bridge, token, 'performance/entries.json', performanceSnapshot())));
    await stage('hook', async () => {
      const hook = hookSnapshot();
      sent.push(send(bridge, token, 'timeline/events.json', { installed:hook.installed,startedAt:hook.startedAt,dropped:hook.droppedEvents,events:hook.events || [] }));
      sent.push(send(bridge, token, 'performance/observer.json', { installed:hook.installed,types:hook.performanceObserverTypes || [],dropped:hook.droppedPerformance,entries:hook.performance || [] }));
      sent.push(send(bridge, token, 'timeline/hook-status.json', { installed:hook.installed,version:hook.version,attachShadowPatched:hook.attachShadowPatched,mutationObserverInstalled:hook.mutationObserverInstalled,closedRootCount:hook.closedRootCount }));
    });
    await stage('browser-environment', async () => sent.push(send(bridge, token, 'environment/browser.json', scrubObject(await browserEnvironment()))));
    await stage('storage', async () => sent.push(send(bridge, token, 'storage/metadata.json', await storageSnapshot())));
    await stage('service-workers', async () => sent.push(send(bridge, token, 'service-workers/metadata.json', await serviceWorkers())));
    await stage('cache', async () => sent.push(send(bridge, token, 'cache/metadata.json', await cacheMetadata())));
    await stage('permissions', async () => sent.push(send(bridge, token, 'permissions/metadata.json', await permissions())));
    await stage('webgl', async () => sent.push(send(bridge, token, 'graphics/webgl.json', webGl())));
    await stage('scripts', async () => sent.push(send(bridge, token, 'resources/scripts.json', await scriptResources())));
    await stage('links-meta', async () => sent.push(send(bridge, token, 'resources/links-meta.json', linksAndMeta())));
    await stage('framework', async () => sent.push(send(bridge, token, 'framework/markers.json', frameworkMarkers())));

    const summary = {
      schemaVersion:VERSION,
      capturedAt:new Date().toISOString(),
      url:safeUrl(location.href),
      sent,
      failures,
      redaction:{ rawCookieValues:false,rawStorageValues:false,passwordValues:false,hiddenInputValues:false,inlineScriptBodies:false,nonceValues:false }
    };
    bridge.complete(token, json(summary));
    return summary;
  };

  Object.defineProperty(window, '__GPT_WEB_CAPTURE__', {
    value:{ version:VERSION,run },
    configurable:true,
    enumerable:false,
    writable:false
  });
})();
