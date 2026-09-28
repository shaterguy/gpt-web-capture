// ==UserScript==
// @name         AVsee ad-gate load shim
// @namespace    local.avsee
// @version      0.1.0
// @description  Keep the ad script blocked, but convert its blocked load into the success signal expected by the player gate.
// @match        https://02.avsee.is/player/player.php*
// @run-at       document-start
// ==/UserScript==

(() => {
  'use strict';

  const TARGET = 'https://cupbearergrowllurch.com/on.js';
  const handled = new WeakSet();

  function isTarget(node) {
    return node instanceof HTMLScriptElement &&
      typeof node.src === 'string' &&
      node.src.startsWith(TARGET);
  }

  function spoofLoad(node) {
    if (!isTarget(node) || handled.has(node)) return;

    handled.add(node);
    node.onerror = null;
    node.removeAttribute('onerror');

    queueMicrotask(() => {
      node.dispatchEvent(new Event('load'));
    });
  }

  window.addEventListener('error', (event) => {
    const node = event.target;
    if (!isTarget(node)) return;

    event.preventDefault();
    event.stopImmediatePropagation();
    spoofLoad(node);
  }, true);

  const observer = new MutationObserver((records) => {
    for (const record of records) {
      for (const node of record.addedNodes) {
        if (isTarget(node)) {
          spoofLoad(node);
          continue;
        }
        if (node instanceof Element) {
          const nested = node.querySelector?.('script[src^="https://cupbearergrowllurch.com/on.js"]');
          if (nested) spoofLoad(nested);
        }
      }
    }
  });

  observer.observe(document, { childList: true, subtree: true });

  const rescan = () => {
    document
      .querySelectorAll('script[src^="https://cupbearergrowllurch.com/on.js"]')
      .forEach(spoofLoad);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', rescan, { once: true });
  } else {
    rescan();
  }
})();
