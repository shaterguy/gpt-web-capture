// ==UserScript==
// @name         AVsee ad-gate neutralizer
// @namespace    local.avsee
// @version      0.2.0
// @description  Keep ads blocked while forcing AVsee's ad gate into the non-blocking unknown state.
// @match        https://02.avsee.is/player/player.php*
// @run-at       document-start
// ==/UserScript==

(() => {
  'use strict';

  const fakeGate = Object.freeze({
    check: async () => 'unknown'
  });

  try {
    Object.defineProperty(window, 'AminaPlayerAdGate', {
      configurable: false,
      enumerable: true,
      get() { return fakeGate; },
      set(_) {}
    });
  } catch (_) {
    try { window.AminaPlayerAdGate = fakeGate; } catch (_) {}
  }

  const mark = () => {
    try { document.documentElement.dataset.avseeGateNeutralizer = '0.2.0'; } catch (_) {}
  };

  if (document.documentElement) mark();
  else {
    const observer = new MutationObserver((_, observer) => {
      if (document.documentElement) {
        mark();
        observer.disconnect();
      }
    });
    observer.observe(document, { childList: true, subtree: true });
  }
})();
