(function () {
  var isTopWindow = false;
  try { isTopWindow = (window.top === window.self); } catch (e) { isTopWindow = false; }
  if (!isTopWindow) return; // Never let ads/embeds/iframes (e.g. popupx.ladi.me) hijack main crawl loop
  if (window.__MYTH_CRAWLER_LOADED__) return;
  window.__MYTH_CRAWLER_LOADED__ = true;

  var CONTROL = "/__myth_crawl";
  var clickableNodes = new WeakSet();
  var pendingNet = 0;
  var runningLoop = false;
  var currentConfig = null;

  // 1. Hook EventTarget.addEventListener synchronously before React/Vue/Angular mount
  try {
    var origAdd = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function (type, listener, options) {
      if (type && (type === "click" || type === "mousedown" || type === "pointerdown" || type === "submit")) {
        try {
          if (this && this.nodeType === 1) {
            clickableNodes.add(this);
            this.setAttribute("data-myth-clickable", "1");
          }
        } catch (e) {}
      }
      return origAdd.apply(this, arguments);
    };
  } catch (e) {}

  // 2. Track active fetch/XHR counts (excluding /__myth_crawl control calls)
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var urlStr = typeof input === "string" ? input : (input && input.url ? input.url : "");
      var isControl = urlStr.indexOf(CONTROL + "/") >= 0;
      if (!isControl) pendingNet++;
      return origFetch.apply(this, arguments).then(
        function (res) { if (!isControl) pendingNet = Math.max(0, pendingNet - 1); return res; },
        function (err) { if (!isControl) pendingNet = Math.max(0, pendingNet - 1); throw err; }
      );
    };
  }

  try {
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) {
      this.__mythUrl = u || "";
      return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var self = this;
      var isControl = (self.__mythUrl || "").indexOf(CONTROL + "/") >= 0;
      if (!isControl) {
        pendingNet++;
        self.addEventListener("loadend", function () {
          pendingNet = Math.max(0, pendingNet - 1);
        }, { once: true });
      }
      return origSend.apply(this, arguments);
    };
  } catch (e) {}

  function apiCall(path, method, payload) {
    var opts = { method: method || "GET", credentials: "same-origin" };
    if (payload !== undefined) {
      opts.headers = { "Content-Type": "application/json" };
      opts.body = JSON.stringify(payload);
    }
    return origFetch.call(window, path, opts).then(function (r) { return r.json(); });
  }

  // Non-blocking dialogs when RUNNING crawl
  var origAlert = window.alert;
  var origConfirm = window.confirm;
  var origPrompt = window.prompt;
  var origWindowOpen = window.open;

  function installCrawlGuards() {
    window.alert = function () {};
    window.confirm = function () { return true; };
    window.prompt = function (msg, def) { return def || "test"; };
    window.open = function (url) {
      if (url && typeof url === "string") {
        try {
          var abs = new URL(url, location.href).href;
          apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: [abs], skipped: [] }).catch(function () {});
        } catch (e) {}
      }
      return null;
    };
  }

  function restoreDialogs() {
    window.alert = origAlert;
    window.confirm = origConfirm;
    window.prompt = origPrompt;
    window.open = origWindowOpen;
  }

  // Tiny unobtrusive status badge
  var badge = null;
  function updateBadge(text, bg) {
    try {
      if (!document.body) return;
      if (!badge) {
        badge = document.createElement("div");
        badge.setAttribute("data-myth-ui", "1");
        badge.style.cssText =
          "position:fixed;right:10px;bottom:10px;z-index:2147483647;" +
          "padding:5px 10px;border-radius:14px;font:12px/1.3 sans-serif;" +
          "color:#fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);pointer-events:auto;cursor:pointer;opacity:0.9;";
        badge.title = "Bấm để Tạm dừng Auto-Crawl";
        badge.addEventListener("click", function (ev) {
          ev.stopPropagation();
          apiCall(CONTROL + "/pause", "POST", {}).catch(function () {});
        });
        document.body.appendChild(badge);
      }
      badge.textContent = "theMythCrawl: " + text;
      badge.style.background = bg || "#374151";
    } catch (e) {}
  }

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  async function waitForNetworkSettle(minWaitMs, maxWaitMs) {
    var start = Date.now();
    await sleep(Math.max(200, minWaitMs || 500));
    while (Date.now() - start < (maxWaitMs || 2500)) {
      if (pendingNet <= 0) {
        await sleep(180);
        if (pendingNet <= 0) break;
      }
      await sleep(120);
    }
  }

  // Exact route + UI provenance formatter (preserves original casing!)
  function sanitizeLabel(raw) {
    if (!raw) return "";
    return String(raw)
      .replace(/[\r\n\t]+/g, " ")
      .replace(/>/g, "›")
      .replace(/'/g, "’")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 48);
  }

  function routeSegments(urlStr, normalizeIds) {
    var path = "/";
    var hash = "";
    try {
      var u = new URL(urlStr, location.href);
      path = u.pathname || "/";
      hash = u.hash ? u.hash.slice(1) : "";
    } catch (e) {}
    var chosen = path;
    if (hash && (hash.startsWith("/") || hash.startsWith("!/"))) {
      var hp = hash.startsWith("!/") ? hash.slice(1) : hash;
      if (hp !== "/" || chosen === "/" || chosen === "") chosen = hp;
    }
    var segs = [];
    chosen.split("/").forEach(function (s) {
      var t = s.replace(/[\r\n\t]/g, " ").replace(/>/g, "›").trim();
      if (!t) return;
      if (normalizeIds && (/^\d+$/.test(t) || /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(t) || /^[0-9a-f]{16,}$/i.test(t))) {
        t = "{id}";
      }
      segs.push(t);
    });
    return segs;
  }

  function formatActionPath(urlStr, kind, label, normalizeIds) {
    var parts = ["/"].concat(routeSegments(urlStr, normalizeIds));
    if (!kind || kind === "page load") {
      parts.push("page load");
    } else if (label) {
      parts.push(kind + " '" + label + "'");
    } else {
      parts.push(kind);
    }
    return parts.join(" > ");
  }

  function isVisible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    if (el.disabled || el.getAttribute("aria-disabled") === "true") return false;
    if (el.closest("[data-myth-ui='1']")) return false;
    var style = window.getComputedStyle(el);
    if (!style || style.display === "none" || style.visibility === "hidden" || style.opacity === "0") return false;
    var rect = el.getBoundingClientRect();
    return rect.width > 1 && rect.height > 1;
  }

  function extractElementInfo(el, idx) {
    var tag = (el.tagName || "").toLowerCase();
    var role = (el.getAttribute("role") || "").toLowerCase();
    var kind = (tag === "a" || role === "link") ? "link" : "button";

    var text = sanitizeLabel(el.innerText || el.textContent);
    if (!text) text = sanitizeLabel(el.getAttribute("aria-label"));
    if (!text) text = sanitizeLabel(el.value);
    if (!text) text = sanitizeLabel(el.getAttribute("title"));
    if (!text) text = sanitizeLabel(el.getAttribute("placeholder"));
    if (!text) text = sanitizeLabel(el.getAttribute("name"));
    if (!text) text = sanitizeLabel(el.id);
    if (!text) text = "#" + (idx + 1);

    var sig = [tag, role, el.id || "", el.getAttribute("name") || "", el.getAttribute("href") || "", text].join("|");
    return { kind: kind, label: text, signature: sig, tag: tag };
  }

  function isDangerousElement(el, info, dangerKeywords) {
    if (!dangerKeywords || !dangerKeywords.length) return null;
    var haystack = [
      info.label,
      el.getAttribute("aria-label") || "",
      el.getAttribute("title") || "",
      el.getAttribute("href") || "",
      el.id || "",
      el.getAttribute("name") || ""
    ].join(" ").toLowerCase();

    for (var i = 0; i < dangerKeywords.length; i++) {
      var kw = dangerKeywords[i];
      if (kw && haystack.indexOf(kw) >= 0) {
        return kw;
      }
    }
    return null;
  }

  function collectAllInteractive(root, out, seenEls) {
    if (!root || !root.querySelectorAll) return;
    var selector = [
      "button",
      "input[type='submit']",
      "input[type='button']",
      "input[type='reset']",
      "a[href]",
      "[role='button']",
      "[role='link']",
      "[role='menuitem']",
      "[role='tab']",
      "summary",
      "[onclick]",
      "[data-myth-clickable='1']"
    ].join(",");

    var nodes = root.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!seenEls.has(el) && isVisible(el)) {
        seenEls.add(el);
        out.push(el);
      }
    }
    // Check cursor:pointer elements (div/span buttons in SPA) and open shadowRoots / same-origin iframes
    var all = root.querySelectorAll("*");
    for (var j = 0; j < all.length; j++) {
      var node = all[j];
      if (clickableNodes.has(node) && !seenEls.has(node) && isVisible(node)) {
        seenEls.add(node);
        out.push(node);
      }
      if (node.shadowRoot) {
        collectAllInteractive(node.shadowRoot, out, seenEls);
      }
      if (node.tagName === "IFRAME") {
        try {
          if (node.contentDocument) collectAllInteractive(node.contentDocument, out, seenEls);
        } catch (e) {}
      }
    }
  }

  function discoverLinks(root, outUrls) {
    if (!root || !root.querySelectorAll) return;
    var anchors = root.querySelectorAll("a[href], area[href], form[action]");
    for (var i = 0; i < anchors.length; i++) {
      var el = anchors[i];
      var raw = el.getAttribute("href") || el.getAttribute("action");
      if (!raw || raw.startsWith("javascript:") || raw.startsWith("mailto:") || raw.startsWith("tel:")) continue;
      try {
        var abs = new URL(raw, location.href).href;
        if (abs.indexOf(location.origin) === 0) outUrls.push(abs);
      } catch (e) {}
    }
  }

  function setNativeInputValue(input, val) {
    try {
      var proto = input.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var desc = Object.getOwnPropertyDescriptor(proto, "value");
      if (desc && desc.set) desc.set.call(input, val);
      else input.value = val;
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    } catch (e) {}
  }

  function autoFillNearestForm(triggerEl) {
    var scope = triggerEl.closest("form,[role='dialog'],.modal,section,main") || document.body;
    if (!scope) return;
    var inputs = scope.querySelectorAll("input, textarea, select");
    for (var i = 0; i < inputs.length; i++) {
      var inp = inputs[i];
      if (inp.disabled || inp.readOnly || !isVisible(inp)) continue;
      var tag = inp.tagName;
      if (tag === "SELECT") {
        if (inp.options && inp.options.length > 0 && (!inp.value || inp.selectedIndex === 0)) {
          for (var o = 0; o < inp.options.length; o++) {
            if (inp.options[o].value) {
              inp.selectedIndex = o;
              inp.dispatchEvent(new Event("change", { bubbles: true }));
              break;
            }
          }
        }
        continue;
      }
      var type = (inp.type || "text").toLowerCase();
      if (type === "hidden" || type === "submit" || type === "button" || type === "reset" || type === "file") continue;
      if (type === "checkbox" || type === "radio") {
        if (inp.required && !inp.checked) {
          inp.checked = true;
          inp.dispatchEvent(new Event("change", { bubbles: true }));
        }
        continue;
      }
      if (inp.value && String(inp.value).trim() !== "") continue;

      var hint = ((inp.name || "") + " " + (inp.placeholder || "") + " " + (inp.id || "")).toLowerCase();
      var val = "test_crawl";
      if (type === "email" || hint.indexOf("email") >= 0) val = "crawl.test@example.com";
      else if (type === "tel" || hint.indexOf("phone") >= 0 || hint.indexOf("sdt") >= 0) val = "0901234567";
      else if (type === "url" || hint.indexOf("website") >= 0 || hint.indexOf("url") >= 0) val = "https://example.com";
      else if (type === "number" || type === "range") val = inp.min || "1";
      else if (type === "date") val = "2026-11-14";
      else if (type === "password") val = "Test@123456";
      else if (hint.indexOf("name") >= 0 || hint.indexOf("tên") >= 0) val = "Test User";
      else if (tag === "TEXTAREA" || hint.indexOf("message") >= 0 || hint.indexOf("content") >= 0) val = "Test crawl message content";
      setNativeInputValue(inp, val);
    }
  }

  async function hoverDropdownTriggers() {
    try {
      var triggers = document.querySelectorAll("[aria-haspopup='true'], nav li, .dropdown-toggle");
      var maxHover = Math.min(triggers.length, 25);
      for (var i = 0; i < maxHover; i++) {
        var t = triggers[i];
        if (!isVisible(t)) continue;
        t.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
        t.dispatchEvent(new MouseEvent("mouseover", { bubbles: true }));
        await sleep(80);
      }
    } catch (e) {}
  }

  async function runCrawlPass() {
    if (runningLoop) return;
    runningLoop = true;
    installCrawlGuards();
    try {
      var state = await apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href));
      currentConfig = state;
      if (state.status !== "RUNNING") {
        updateBadge(state.status, "#4b5563");
        return;
      }

      updateBadge("Đang quét " + location.pathname, "#0f766e");
      var normIds = !!state.normalizeRouteIds;
      var pageLoadPath = formatActionPath(location.href, "page load", "", normIds);
      await apiCall(CONTROL + "/context", "POST", { actionPath: pageLoadPath, pageUrl: location.href });
      await waitForNetworkSettle(600, 2600);

      await hoverDropdownTriggers();

      var links = [];
      discoverLinks(document, links);
      await apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: links, skipped: [] });

      var clickedSet = new Set(state.clickedSignatures || []);
      var actionsDone = 0;
      var maxActions = state.maxActionsPerPage || 180;

      while (actionsDone < maxActions) {
        var freshState = await apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href));
        if (freshState.status !== "RUNNING") {
          updateBadge(freshState.status, "#b45309");
          return;
        }

        var elements = [];
        collectAllInteractive(document, elements, new WeakSet());
        var skippedBatch = [];
        var targetEl = null;
        var targetInfo = null;

        for (var i = 0; i < elements.length; i++) {
          var el = elements[i];
          var info = extractElementInfo(el, i);
          if (clickedSet.has(info.signature)) continue;

          if (freshState.dangerGuardEnabled) {
            var badKw = isDangerousElement(el, info, freshState.dangerKeywords);
            if (badKw) {
              clickedSet.add(info.signature);
              skippedBatch.push({
                actionPath: formatActionPath(location.href, info.kind, info.label, normIds),
                reason: "Danger Guard keyword: " + badKw,
                tag: info.tag
              });
              await apiCall(CONTROL + "/done_action", "POST", { pageUrl: location.href, signature: info.signature });
              continue;
            }
          }
          targetEl = el;
          targetInfo = info;
          break;
        }

        if (skippedBatch.length > 0) {
          await apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: [], skipped: skippedBatch });
        }

        if (!targetEl) break; // Done all interactive elements on this page!

        clickedSet.add(targetInfo.signature);
        var beforeUrl = location.href;
        var actionPath = formatActionPath(beforeUrl, targetInfo.kind, targetInfo.label, normIds);

        // If <a href> targets another same-origin page, avoid losing remaining buttons on current page prematurely:
        // Queue its href in BFS AND still check if click fires SPA/API via normal click!
        if (targetInfo.kind === "link" && targetEl.getAttribute("href")) {
          var href = targetEl.getAttribute("href");
          if (href && !href.startsWith("#") && !href.startsWith("javascript:")) {
            try {
              var dest = new URL(href, beforeUrl).href;
              if (dest.indexOf(location.origin) === 0 && dest !== beforeUrl) {
                await apiCall(CONTROL + "/discovered", "POST", { pageUrl: beforeUrl, urls: [dest], skipped: [] });
                // Avoid navigating away from current page before finishing all buttons;
                // unless target is hash/SPA link handled without full unload!
                if (!targetEl.hasAttribute("data-myth-clickable") && !targetEl.hasAttribute("onclick")) {
                  await apiCall(CONTROL + "/done_action", "POST", { pageUrl: beforeUrl, signature: targetInfo.signature });
                  actionsDone++;
                  continue;
                }
              }
            } catch (e) {}
          }
        }

        if (targetEl.getAttribute("target") === "_blank") {
          targetEl.setAttribute("target", "_self");
        }

        updateBadge(actionPath, "#1d4ed8");
        await apiCall(CONTROL + "/context", "POST", { actionPath: actionPath, pageUrl: beforeUrl });

        if (freshState.autoFillForms) {
          autoFillNearestForm(targetEl);
        }

        try {
          targetEl.scrollIntoView({ block: "center", inline: "center" });
        } catch (e) {}

        try {
          targetEl.dispatchEvent(new MouseEvent("mousedown", { bubbles: true, cancelable: true }));
          targetEl.click();
        } catch (e) {}

        await waitForNetworkSettle(freshState.actionWaitMs || 550, 2600);

        // Collect any newly revealed links (e.g. inside modal or SPA panel)
        var newLinks = [];
        discoverLinks(document, newLinks);
        if (newLinks.length > 0) {
          await apiCall(CONTROL + "/discovered", "POST", { pageUrl: beforeUrl, urls: newLinks, skipped: [] });
        }

        await apiCall(CONTROL + "/done_action", "POST", { pageUrl: beforeUrl, signature: targetInfo.signature });
        actionsDone++;

        // Handle SPA pushState navigation: queue new route and navigate back to finish remaining buttons!
        if (location.href !== beforeUrl) {
          await apiCall(CONTROL + "/discovered", "POST", { pageUrl: beforeUrl, urls: [location.href], skipped: [] });
          try {
            history.back();
            await sleep(450);
            if (location.href !== beforeUrl) {
              location.href = beforeUrl;
              return;
            }
          } catch (e) {}
        }
      }

      // Move to next URL in Burp Java BFS queue
      var nextResp = await apiCall(CONTROL + "/next_page?url=" + encodeURIComponent(location.href));
      if (nextResp && !nextResp.done && nextResp.nextUrl) {
        updateBadge("Chuyển trang -> " + nextResp.nextUrl, "#0f766e");
        location.href = nextResp.nextUrl;
      } else {
        updateBadge("HOÀN TẤT (" + (nextResp.queueRemaining || 0) + " chờ)", "#15803d");
      }
    } catch (e) {
      updateBadge("Lỗi tạm: " + (e && e.message ? e.message : "check log"), "#b91c1c");
    } finally {
      restoreDialogs();
      runningLoop = false;
    }
  }

  function pollStatusLoop() {
    setInterval(function () {
      if (runningLoop) return;
      apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href))
        .then(function (st) {
          currentConfig = st;
          if (st.status === "RUNNING") {
            runCrawlPass();
          } else {
            updateBadge(st.status === "IDLE" ? "Sẵn sàng (chờ Start trên Burp)" : st.status, "#4b5563");
          }
        })
        .catch(function (err) {
          updateBadge("Lỗi /__myth_crawl (" + (err && err.message ? err.message : "check Proxy") + ")", "#b91c1c");
        });
    }, 900);
  }

  function boot() {
    pollStatusLoop();
    setTimeout(function () {
      apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href))
        .then(function (st) {
          if (st && st.status === "RUNNING") {
            runCrawlPass();
          } else if (st) {
            updateBadge(st.status === "IDLE" ? "Sẵn sàng (chờ Start trên Burp)" : st.status, "#4b5563");
          }
        })
        .catch(function (err) {
          updateBadge("Lỗi kết nối Burp (" + (err && err.message ? err.message : "Proxy?") + ")", "#b91c1c");
        });
    }, 650);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
