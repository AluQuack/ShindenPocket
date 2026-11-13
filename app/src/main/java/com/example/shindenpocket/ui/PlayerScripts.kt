package com.example.shindenpocket.ui

import com.example.shindenpocket.data.EpisodeSource

/**
 * Skrypt neutralizujący komunikaty wykrywania blokady reklam oraz blokujący wyskakujące okna (pop-up),
 * bez fałszowania odsłon i bez udawania pomyślnego załadowania bibliotek reklamowych.
 */
internal val antiAdblockScript: String = """
(function () {
  try {
    if (window.__pocketAB) return;
    window.__pocketAB = true;

    try {
      window.open = function () { return null; };
    } catch (e) {}

    function defineFlag(name, value) {
      try {
        Object.defineProperty(window, name, {
          configurable: true,
          enumerable: true,
          get: function () { return value; },
          set: function () {}
        });
      } catch (e) {
        try { window[name] = value; } catch (_) {}
      }
    }

    defineFlag('canRunAds', true);
    defineFlag('isAdBlockActive', false);
    defineFlag('adblock', false);
    defineFlag('adBlockDetected', false);
    defineFlag('hasAdblock', false);

    var host = (window.location && window.location.hostname || '').toLowerCase();
    if (host === 'shinden.pl' || host.endsWith('.shinden.pl')) {
      var WARN_STYLE_ID = 'shinden-pocket-ab-notice-style';
      var WARN_CSS = [
        '.adblock-info, .adblock-notice, .adblock-warning, .adblock-modal, #adblock-modal, #adb-notice {',
        '  display: none !important; visibility: hidden !important; pointer-events: none !important;',
        '}'
      ].join('\n');

      function injectNoticeFilter() {
        var root = document.head || document.documentElement;
        if (!root || document.getElementById(WARN_STYLE_ID)) return;
        var style = document.createElement('style');
        style.id = WARN_STYLE_ID;
        style.textContent = WARN_CSS;
        root.appendChild(style);
      }

      if (document.documentElement) {
        injectNoticeFilter();
      } else {
        document.addEventListener('DOMContentLoaded', injectNoticeFilter, { once: true });
      }
    }
  } catch (e) {}
})();
""".trimIndent()

/**
 * Skrypt wybierający przycisk wybranego źródła wideo na stronie odcinka Shindena.
 * Obsługuje wyspę React (`.ep-players li`), listę statyczną (`.ep-pl-list` / `.ep-pl-row`)
 * oraz starszą tabelę (`.change-video-player`).
 */
internal fun playerSelectionScript(source: EpisodeSource, index: Int): String {
    val id = quoteJsString(source.playerId.orEmpty())
    val label = quoteJsString(source.service)
    return """
(function () {
  var onlineId = $id;
  var service = $label.toLowerCase().trim();
  var ordinal = $index;
  var selectionKey = onlineId + '|' + service + '|' + ordinal;

  // Okno "Oglądasz bez konta" jest zamykane na cały dzień po ustawieniu klucza
  // w localStorage (data w strefie Europe/Warsaw). Nie wpływa to na uprawnienia.
  try {
    var warsawDay = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Warsaw' }).format(new Date());
    localStorage.setItem('sh.gosc.okno', warsawDay);
  } catch (e) {}

  if (window.__shindenPocketSelectedKey === selectionKey) return;
  if (window.__shindenPocketSelectTimer) {
    clearInterval(window.__shindenPocketSelectTimer);
    window.__shindenPocketSelectTimer = null;
  }

  function reactKeyOf(el) {
    try {
      for (var key in el) {
        if (key.indexOf('__reactFiber${'$'}') !== 0 && key.indexOf('__reactInternalInstance${'$'}') !== 0) continue;
        var fiber = el[key];
        for (var depth = 0; fiber && depth < 3; depth++) {
          if (fiber.key != null && /^[0-9]+$/.test(String(fiber.key))) return String(fiber.key);
          fiber = fiber.return;
        }
      }
    } catch (e) {}
    return '';
  }

  function extractOnlineIdFromRow(row) {
    var attrId = row.getAttribute('data-online-id') || row.getAttribute('data-episode') || row.getAttribute('data-id');
    if (attrId) return String(attrId).trim();
    var reportLink = row.querySelector('a[href*="/report-player/"]');
    if (reportLink) {
      var match = (reportLink.getAttribute('href') || '').match(/\/report-player\/([0-9]+)/);
      if (match) return match[1];
    }
    return reactKeyOf(row);
  }

  function isSuggestedRow(row) {
    return row.id === 'episode-suggested-row' ||
      row.hasAttribute('data-sug') ||
      !!(row.closest && row.closest('#episode-suggested-row')) ||
      !!row.querySelector('[data-sug]');
  }

  function collectRows() {
    var selectors = [
      '.ep-players li',
      '[data-sh-module="episode-players"] li',
      '.ep-pl-list > li',
      '.ep-pl-list .ep-pl-row',
      '.ep-pl-row'
    ];
    var rows = [];
    for (var s = 0; s < selectors.length; s++) {
      var nodes = document.querySelectorAll(selectors[s]);
      for (var n = 0; n < nodes.length; n++) {
        var row = nodes[n];
        if (rows.indexOf(row) !== -1 || isSuggestedRow(row)) continue;
        var hasButton = !!row.querySelector('button, [role="button"], a.ep-pl-row__host');
        if (!hasButton && !row.classList.contains('ep-pl-row')) continue;
        rows.push(row);
      }
    }
    return rows;
  }

  function clickRow(row) {
    var button = row.querySelector('button, [role="button"]');
    if (button) {
      button.click();
      return true;
    }
    var hostLink = row.querySelector('a.ep-pl-row__host');
    if (hostLink) {
      hostLink.click();
      return true;
    }
    if (row.classList.contains('ep-pl-row')) {
      row.click();
      return true;
    }
    return false;
  }

  function trySelectPlayer() {
    var playerRows = collectRows();

    if (playerRows.length > 0) {
      var byId = onlineId ? playerRows.find(function (row) {
        return extractOnlineIdFromRow(row) === onlineId;
      }) : null;
      var byService = service ? playerRows.filter(function (row) {
        return (row.textContent || '').toLowerCase().indexOf(service) !== -1;
      }) : [];
      var chosenRow = byId || byService[0] || playerRows[ordinal] || playerRows[0];
      if (chosenRow && clickRow(chosenRow)) {
        window.__shindenPocketSelectedKey = selectionKey;
        window.__shindenPocketPlayerSelected = true;
        return true;
      }
    }

    var legacyButtons = Array.prototype.slice.call(
      document.querySelectorAll('.change-video-player[data-episode]')
    );
    if (legacyButtons.length > 0) {
      var legacyById = onlineId ? legacyButtons.find(function (el) {
        try {
          var raw = el.getAttribute('data-episode') || '';
          if (raw === onlineId) return true;
          return String(JSON.parse(raw).online_id) === onlineId;
        } catch (e) {
          return false;
        }
      }) : null;
      var legacyByService = service ? legacyButtons.filter(function (el) {
        var tr = el.closest('tr');
        return tr && (tr.textContent || '').toLowerCase().indexOf(service) !== -1;
      }) : [];
      var targetButton = legacyById || legacyByService[0] || legacyButtons[ordinal] || legacyButtons[0];
      if (targetButton) {
        window.__shindenPocketSelectedKey = selectionKey;
        window.__shindenPocketPlayerSelected = true;
        targetButton.click();
        return true;
      }
    }

    return false;
  }

  if (trySelectPlayer()) return;

  var tries = 0;
  window.__shindenPocketSelectTimer = setInterval(function () {
    if (trySelectPlayer() || ++tries >= 40) {
      clearInterval(window.__shindenPocketSelectTimer);
      window.__shindenPocketSelectTimer = null;
    }
  }, 300);
})();
""".trimIndent()
}

/**
 * Skrypt odpowiadający za:
 * 1) ukrywanie kontenerów reklamowych na stronie Shindena,
 * 2) tryb kompaktowy ("tylko odtwarzacz"), wycinający wszystko poza aktywnym iframe/video
 *    przy minimalnej liczbie operacji DOM i filtrowanym `MutationObserver`.
 */
internal fun pocketScript(compact: Boolean): String = """
(function () {
  var W = window;
  W.__pocketCompact = $compact;

  if (!W.__pocketInit) {
    W.__pocketInit = true;
    W.__pocketReady = false;
    W.__pocketFrameLoaded = false;
    W.__pocketFoundAt = 0;
    W.__pocketStart = Date.now();
    W.__pocketTarget = null;
    W.__pocketAppliedCompact = null;

    try { W.open = function () { return null; }; } catch (e) {}

    var STYLE_ID = 'shinden-pocket-player-style';
    var CSS = [
      'ins.adsbygoogle, .adsbygoogle, [id^="google_ads"], [id^="div-gpt-ad"],' +
      ' iframe[src*="doubleclick"], iframe[src*="googlesyndication"], iframe[src*="adsterra"],' +
      ' iframe[src*="exoclick"], iframe[src*="propeller"]' +
      ' { display: none !important; }',
      'html.pp, html.pp body { margin: 0 !important; padding: 0 !important; background: #000 !important;' +
      ' overflow: hidden !important; width: 100% !important; height: 100% !important; }',
      '.pp-hide { display: none !important; }',
      '.pp-path { position: relative !important; width: 100% !important; height: 100% !important;' +
      ' min-height: 0 !important; max-width: none !important; margin: 0 !important; padding: 0 !important;' +
      ' border: 0 !important; transform: none !important; filter: none !important;' +
      ' overflow: hidden !important; background: #000 !important; }',
      '.pp-player { position: fixed !important; left: 0 !important; top: 0 !important;' +
      ' width: 100vw !important; height: 100vh !important; max-width: none !important;' +
      ' min-height: 0 !important; margin: 0 !important; padding: 0 !important; border: 0 !important;' +
      ' z-index: 2147483000 !important; background: #000 !important; visibility: visible !important; }'
    ].join('\n');

    var markedNodes = [];

    function markClass(el, className) {
      if (!el || !el.classList) return;
      if (!el.classList.contains(className)) {
        el.classList.add(className);
      }
      if (!el.__ppTracked) {
        el.__ppTracked = true;
        markedNodes.push(el);
      }
    }

    function ensureStyle() {
      if (document.getElementById(STYLE_ID)) return;
      var root = document.head || document.documentElement;
      if (!root) return;
      var s = document.createElement('style');
      s.id = STYLE_ID;
      s.textContent = CSS;
      root.appendChild(s);
    }

    function clearMarks() {
      for (var i = 0; i < markedNodes.length; i++) {
        var node = markedNodes[i];
        if (node && node.classList) {
          node.classList.remove('pp-hide', 'pp-path', 'pp-player');
        }
        if (node) {
          node.__ppTracked = false;
        }
      }
      markedNodes.length = 0;
      if (document.documentElement) {
        document.documentElement.classList.remove('pp', 'pp-wait');
      }
    }

    var IGNORED_SRC = /(doubleclick|googlesyndication|googleadservices|adsterra|exoclick|propeller|popads|criteo|taboola|outbrain|facebook\.com|disqus\.com|recaptcha|reklama\.shinden|adpeeps|spolecznosci|challenges\.cloudflare|turnstile|jenonaw|velocecdn|visariomedia|gmyyuqkubaycjc|dxuyhdxnr|llvpn)/i;

    function isProtectedElement(el) {
      if (!el || el.nodeType !== 1) return false;
      if (el.id === 'ep-komunikat' || el.id === 'sh-turnstile') return true;
      if (el.classList && (
        el.classList.contains('takie-tam') ||
        el.classList.contains('change-video-player')
      )) {
        return true;
      }
      return !!(el.querySelector && el.querySelector('.change-video-player, .takie-tam'));
    }

    function isAdContainer(el) {
      return !!(el && el.closest && el.closest(
        '#sh-turnstile, .ep-ad-center, .takie-tam, .ads, #ad-player, #ad_tmpl, #ad_tmpl2, .spolecznoscinet'
      ));
    }

    function isValidMediaCandidate(el, inDedicatedBlock) {
      if (!el || el.nodeType !== 1) return false;
      if (isAdContainer(el)) return false;
      var tag = el.tagName;
      if (tag === 'IFRAME') {
        var src = (el.getAttribute('src') || el.src || '').trim();
        if (src && IGNORED_SRC.test(src)) return false;
        if (!inDedicatedBlock && (!src || src === 'about:blank' || src.indexOf('javascript:') === 0)) {
          return false;
        }
      } else if (tag !== 'VIDEO') {
        return false;
      }
      return true;
    }

    function findPlayer() {
      var dedicatedContainers = [
        document.getElementById('player-block'),
        document.querySelector('.ep-online-player'),
        document.getElementById('player-container'),
        document.querySelector('.player-container')
      ];

      for (var c = 0; c < dedicatedContainers.length; c++) {
        var container = dedicatedContainers[c];
        if (!container) continue;
        var candidates = container.querySelectorAll('iframe, video');
        var bestInContainer = null;
        var bestContainerArea = 0;
        for (var i = 0; i < candidates.length; i++) {
          var cand = candidates[i];
          if (!isValidMediaCandidate(cand, true)) continue;
          var rect = cand.getBoundingClientRect();
          var area = rect.width * rect.height;
          if (rect.width >= 160 && rect.height >= 90 && area > bestContainerArea) {
            bestInContainer = cand;
            bestContainerArea = area;
          } else if (!bestInContainer) {
            var src = (cand.getAttribute('src') || '').trim();
            if (cand.tagName === 'VIDEO' || (src && src !== 'about:blank')) {
              bestInContainer = cand;
            }
          }
        }
        if (bestInContainer) return bestInContainer;
      }

      var allMedia = document.querySelectorAll('iframe, video');
      var best = null;
      var bestArea = 0;
      for (var j = 0; j < allMedia.length; j++) {
        var el = allMedia[j];
        if (!isValidMediaCandidate(el, false)) continue;
        var r = el.getBoundingClientRect();
        if (r.width < 200 || r.height < 100) continue;
        var a = r.width * r.height;
        if (a > bestArea) {
          best = el;
          bestArea = a;
        }
      }
      return best;
    }

    W.__pocketApply = function (forceReapply) {
      ensureStyle();
      var root = document.documentElement;
      if (!root) return;

      if (!W.__pocketCompact) {
        if (W.__pocketAppliedCompact !== false || markedNodes.length > 0) {
          clearMarks();
          W.__pocketAppliedCompact = false;
        }
        W.__pocketTarget = null;
        W.__pocketReady = true;
        W.__pocketFrameLoaded = true;
        return;
      }

      var target = findPlayer();
      if (!target) {
        if (W.__pocketTarget || markedNodes.length > 0) {
          clearMarks();
          W.__pocketTarget = null;
        }
        if (Date.now() - W.__pocketStart > 12000) {
          W.__pocketReady = true;
          W.__pocketFrameLoaded = true;
        }
        W.__pocketAppliedCompact = true;
        return;
      }

      if (
        !forceReapply &&
        W.__pocketAppliedCompact === true &&
        W.__pocketTarget === target &&
        target.isConnected &&
        target.classList.contains('pp-player')
      ) {
        root.classList.remove('pp-wait');
        W.__pocketReady = true;
        return;
      }

      clearMarks();
      root.classList.add('pp');
      root.classList.remove('pp-wait');

      if (W.__pocketTarget !== target) {
        W.__pocketTarget = target;
        W.__pocketFrameLoaded = false;
        W.__pocketFoundAt = Date.now();
        var done = function () {
          if (W.__pocketTarget === target) {
            W.__pocketFrameLoaded = true;
          }
        };
        target.addEventListener('load', done, { once: true });
        target.addEventListener('loadeddata', done, { once: true });
      }

      for (var el = target; el && el !== root; el = el.parentElement) {
        if (el !== target) {
          markClass(el, 'pp-path');
        }
        var parent = el.parentElement;
        if (parent) {
          var children = parent.children;
          for (var k = 0; k < children.length; k++) {
            var sib = children[k];
            var t = sib.tagName;
            if (sib !== el && t !== 'SCRIPT' && t !== 'STYLE' && t !== 'LINK' && t !== 'HEAD' &&
                !isProtectedElement(sib)) {
              markClass(sib, 'pp-hide');
            }
          }
        }
      }
      markClass(target, 'pp-player');
      W.__pocketAppliedCompact = true;
      W.__pocketReady = true;
    };

    function isRelevantNode(node) {
      if (!node || node.nodeType !== 1) return false;
      var tag = node.tagName;
      if (tag === 'STYLE' || tag === 'SCRIPT' || tag === 'LINK') return false;
      if (tag === 'IFRAME' || tag === 'VIDEO') return true;
      if (node.id === 'player-block' || node.classList && (
        node.classList.contains('ep-online-player') ||
        node.classList.contains('player-container') ||
        node.classList.contains('ep-players')
      )) {
        return true;
      }
      return !!(node.querySelector && node.querySelector('iframe, video'));
    }

    function hasRelevantMutation(mutations) {
      if (!W.__pocketCompact) return false;
      var current = W.__pocketTarget;
      if (!current || !current.isConnected) return true;

      for (var i = 0; i < mutations.length; i++) {
        var m = mutations[i];
        var added = m.addedNodes;
        for (var a = 0; a < added.length; a++) {
          if (isRelevantNode(added[a])) return true;
        }
        var removed = m.removedNodes;
        for (var r = 0; r < removed.length; r++) {
          if (removed[r] === current || isRelevantNode(removed[r])) return true;
        }
      }
      return false;
    }

    var debounceTimer = null;
    var observer = new MutationObserver(function (mutations) {
      if (debounceTimer) return;
      if (!hasRelevantMutation(mutations)) return;
      debounceTimer = setTimeout(function () {
        debounceTimer = null;
        W.__pocketApply(false);
      }, 100);
    });

    if (document.documentElement) {
      observer.observe(document.documentElement, { childList: true, subtree: true });
    }
  }
  W.__pocketApply(true);
})();
""".trimIndent()

internal fun quoteJsString(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '<', '>', '\u2028', '\u2029' -> {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            }
            else -> if (ch < ' ') {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}
