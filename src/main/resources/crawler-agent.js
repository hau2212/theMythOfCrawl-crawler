(function () {
  var isTopWindow = false;
  try { isTopWindow = (window.top === window.self); } catch (e) { isTopWindow = false; }
  if (!isTopWindow) return; // Never let cross-origin ads/embeds/iframes hijack top-level crawl loop
  if (window.__MYTH_CRAWLER_LOADED__) return;
  window.__MYTH_CRAWLER_LOADED__ = true;

  var CONTROL = "/__myth_crawl";
  var clickableNodes = new WeakSet();
  var hookedWindows = new WeakSet();
  var pendingNet = 0;
  var runningLoop = false;
  var currentConfig = null;
  var lastContextErrorAt = 0;

  var INTERACTIVE_TYPES = {
    click: 1, mousedown: 1, mouseup: 1, pointerdown: 1, pointerup: 1,
    submit: 1, change: 1, touchstart: 1
  };

  function hookWindowRuntime(win) {
    try {
      if (!win || hookedWindows.has(win)) return;
      hookedWindows.add(win);

      // 1. Capture framework listeners (Vue, Svelte, Lit, plain JS, etc.) synchronously
      if (win.EventTarget && win.EventTarget.prototype && win.EventTarget.prototype.addEventListener) {
        var origAdd = win.EventTarget.prototype.addEventListener;
        win.EventTarget.prototype.addEventListener = function (type, listener, options) {
          if (type && INTERACTIVE_TYPES[String(type).toLowerCase()]) {
            try {
              if (this && this.nodeType === 1 && this.tagName !== "BODY" && this.tagName !== "HTML" && this.id !== "root" && this.id !== "app") {
                clickableNodes.add(this);
                this.setAttribute("data-myth-clickable", "1");
              }
            } catch (e) {}
          }
          return origAdd.apply(this, arguments);
        };
      }

      // 2. Track active fetch / XHR / sendBeacon / EventSource so we wait accurately for APIs
      if (win.fetch) {
        var wOrigFetch = win.fetch;
        win.fetch = function (input) {
          var urlStr = typeof input === "string" ? input : (input && input.url ? input.url : "");
          var isControl = urlStr.indexOf(CONTROL + "/") >= 0;
          if (!isControl) pendingNet++;
          return wOrigFetch.apply(this, arguments).then(
            function (res) { if (!isControl) pendingNet = Math.max(0, pendingNet - 1); return res; },
            function (err) { if (!isControl) pendingNet = Math.max(0, pendingNet - 1); throw err; }
          );
        };
      }

      if (win.XMLHttpRequest && win.XMLHttpRequest.prototype) {
        var origOpen = win.XMLHttpRequest.prototype.open;
        var origSend = win.XMLHttpRequest.prototype.send;
        win.XMLHttpRequest.prototype.open = function (m, u) {
          this.__mythUrl = u || "";
          return origOpen.apply(this, arguments);
        };
        win.XMLHttpRequest.prototype.send = function () {
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
      }

      if (win.navigator && typeof win.navigator.sendBeacon === "function") {
        var origBeacon = win.navigator.sendBeacon.bind(win.navigator);
        win.navigator.sendBeacon = function (u) {
          if (typeof u === "string" && u.indexOf(CONTROL + "/") < 0 && u.indexOf(location.origin) === 0) {
            pendingNet++;
            setTimeout(function () { pendingNet = Math.max(0, pendingNet - 1); }, 350);
          }
          return origBeacon.apply(win.navigator, arguments);
        };
      }
    } catch (e) {}
  }

  var topFetch = window.fetch.bind(window);
  hookWindowRuntime(window);

  function apiCall(path, method, payload) {
    var opts = { method: method || "GET", credentials: "same-origin" };
    if (payload !== undefined) {
      opts.headers = { "Content-Type": "application/json" };
      opts.body = JSON.stringify(payload);
    }
    return topFetch(path, opts).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    });
  }

  // Non-blocking dialog guards while crawl is RUNNING
  var origAlert = window.alert;
  var origConfirm = window.confirm;
  var origPrompt = window.prompt;
  var origPrint = window.print;
  var origWindowOpen = window.open;

  function installCrawlGuards() {
    window.alert = function () {};
    window.confirm = function () { return true; };
    window.prompt = function (msg, def) {
      var fallback = (currentConfig && currentConfig.formFill && currentConfig.formFill.text) || "TEXT";
      return def || fallback;
    };
    window.print = function () {};
    window.open = function (url) {
      if (url && typeof url === "string") {
        try {
          var abs = new URL(url, location.href).href;
          if (abs.indexOf(location.origin) === 0) {
            apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: [abs], skipped: [] }).catch(function () {});
          }
        } catch (e) {}
      }
      return null;
    };
  }

  function restoreDialogs() {
    window.alert = origAlert;
    window.confirm = origConfirm;
    window.prompt = origPrompt;
    window.print = origPrint;
    window.open = origWindowOpen;
  }

  // Unobtrusive floating status pill
  var badge = null;
  function updateBadge(text, bg) {
    try {
      if (!document.body) return;
      if (!badge) {
        badge = document.createElement("div");
        badge.setAttribute("data-myth-ui", "1");
        badge.style.cssText =
          "position:fixed;right:12px;bottom:12px;z-index:2147483647;" +
          "padding:6px 12px;border-radius:16px;font:600 12px/1.35 system-ui,sans-serif;" +
          "color:#fff;box-shadow:0 3px 12px rgba(0,0,0,0.32);pointer-events:auto;cursor:pointer;opacity:0.94;";
        badge.title = "Bấm để Tạm dừng Auto-Crawl";
        badge.addEventListener("click", function (ev) {
          ev.stopPropagation();
          apiCall(CONTROL + "/pause", "POST", {}).catch(function () {});
        });
        document.body.appendChild(badge);
      }
      badge.textContent = "theMythCrawl: " + text;
      badge.style.background = bg || "#334155";
    } catch (e) {}
  }

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  async function waitForNetworkSettle(minWaitMs, maxWaitMs) {
    var start = Date.now();
    await sleep(Math.max(220, minWaitMs || 550));
    while (Date.now() - start < (maxWaitMs || 3200)) {
      if (pendingNet <= 0) {
        await sleep(220);
        if (pendingNet <= 0) break;
      }
      await sleep(120);
    }
  }

  // Hierarchy Action Path formatter keeping original UI casing
  function sanitizeLabel(raw) {
    if (!raw) return "";
    return String(raw)
      .replace(/[\r\n\t]+/g, " ")
      .replace(/>/g, "›")
      .replace(/'/g, "’")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 56);
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

  function formatActionPath(urlStr, kind, label, stateObj) {
    var normalizeIds = !stateObj || !!stateObj.normalizeRouteIds;
    var rootLabel = (stateObj && stateObj.routeRootLabel) ? String(stateObj.routeRootLabel).trim() : "toppage";
    if (!rootLabel) rootLabel = "toppage";
    var parts = [rootLabel].concat(routeSegments(urlStr, normalizeIds));
    if (!kind || kind === "page load") {
      parts.push("page load");
    } else if (label) {
      parts.push(kind + " '" + label + "'");
    } else {
      parts.push(kind);
    }
    return parts.join(" > ");
  }

  function getDocView(el) {
    try {
      return (el.ownerDocument && el.ownerDocument.defaultView) || window;
    } catch (e) {
      return window;
    }
  }

  function isVisible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    try {
      if (el.disabled || el.getAttribute("aria-disabled") === "true") return false;
      if (el.closest && el.closest("[data-myth-ui='1']")) return false;
      var view = getDocView(el);
      var style = view.getComputedStyle(el);
      if (!style || style.display === "none" || style.visibility === "hidden" || style.opacity === "0") return false;
      var rect = el.getBoundingClientRect();
      return rect.width > 1 && rect.height > 1;
    } catch (e) {
      return false;
    }
  }

  function extractElementInfo(el, idx, framePrefix) {
    var tag = (el.tagName || "").toLowerCase();
    var role = (el.getAttribute("role") || "").toLowerCase();
    var type = (el.getAttribute("type") || "").toLowerCase();

    var kind = "button";
    if (tag === "a" || role === "link") kind = "link";
    else if (tag === "select") kind = "select";
    else if (role === "tab") kind = "tab";
    else if (role === "menuitem" || role === "menuitemcheckbox" || role === "menuitemradio") kind = "menu";
    else if (role === "switch" || (tag === "input" && (type === "checkbox" || type === "radio"))) kind = "toggle";
    else if (tag === "input" && (type === "search" || type === "text")) kind = "search";

    var text = sanitizeLabel(el.innerText || el.textContent);
    if (!text) text = sanitizeLabel(el.getAttribute("aria-label"));
    if (!text) text = sanitizeLabel(el.value);
    if (!text) text = sanitizeLabel(el.getAttribute("title"));
    if (!text && el.querySelector) {
      var img = el.querySelector("img[alt], svg[aria-label]");
      if (img) text = sanitizeLabel(img.getAttribute("alt") || img.getAttribute("aria-label"));
    }
    if (!text) text = sanitizeLabel(el.getAttribute("placeholder"));
    if (!text) text = sanitizeLabel(el.getAttribute("name"));
    if (!text) text = sanitizeLabel(el.id);
    if (!text) text = "#" + (idx + 1);

    var sig = [framePrefix || "", tag, role, type, el.id || "", el.getAttribute("name") || "", el.getAttribute("href") || "", text].join("|");
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

  function isReasonablePointerWidget(node, view) {
    try {
      var tag = node.tagName;
      if (tag === "HTML" || tag === "BODY" || tag === "MAIN" || tag === "SECTION" || tag === "HEADER" || tag === "FOOTER") {
        return false;
      }
      var rect = node.getBoundingClientRect();
      // Skip gigantic layout wrappers that happen to inherit cursor:pointer
      if (rect.width > view.innerWidth * 0.85 && rect.height > view.innerHeight * 0.65) {
        return false;
      }
      if (clickableNodes.has(node)) return true;
      var cs = view.getComputedStyle(node);
      if (cs && cs.cursor === "pointer") {
        // Avoid duplicate clicks on nested <span> inside an already queued <button>/<a>
        if (node.closest && node.closest("button, a[href], [role='button'], [role='menuitem'], [role='tab']")) {
          return false;
        }
        return true;
      }
    } catch (e) {}
    return false;
  }

  function collectAllInteractive(root, out, seenEls, frameDepth, framePrefix) {
    if (!root || !root.querySelectorAll || frameDepth > 4) return;
    var selector = [
      "button",
      "input[type='submit']",
      "input[type='button']",
      "input[type='image']",
      "input[type='reset']",
      "select",
      "a[href]",
      "[role='button']",
      "[role='link']",
      "[role='menuitem']",
      "[role='menuitemcheckbox']",
      "[role='menuitemradio']",
      "[role='tab']",
      "[role='switch']",
      "[role='option']",
      "[role='treeitem']",
      "summary",
      "[onclick]",
      "[ng-click]",
      "[hx-get]",
      "[hx-post]",
      "[hx-put]",
      "[hx-delete]",
      "[hx-patch]",
      "[wire\\:click]",
      "[tabindex='0']",
      "[data-myth-clickable='1']"
    ].join(",");

    var nodes = root.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!seenEls.has(el) && isVisible(el)) {
        seenEls.add(el);
        out.push({ el: el, framePrefix: framePrefix || "" });
      }
    }

    var view = (root.defaultView) || (root.ownerDocument && root.ownerDocument.defaultView) || window;
    var all = root.querySelectorAll("*");
    var limit = Math.min(all.length, 4000);
    for (var j = 0; j < limit; j++) {
      var node = all[j];
      if (!seenEls.has(node) && isVisible(node) && isReasonablePointerWidget(node, view)) {
        seenEls.add(node);
        out.push({ el: node, framePrefix: framePrefix || "" });
      }
      if (node.shadowRoot) {
        collectAllInteractive(node.shadowRoot, out, seenEls, frameDepth + 1, framePrefix);
      }
      if (node.tagName === "IFRAME" || node.tagName === "FRAME") {
        try {
          if (node.contentWindow) hookWindowRuntime(node.contentWindow);
          if (node.contentDocument) {
            var fn = node.getAttribute("name") || node.id || ("frame" + frameDepth);
            collectAllInteractive(node.contentDocument, out, seenEls, frameDepth + 1, fn);
          }
        } catch (e) {}
      }
    }
  }

  function discoverLinks(root, outUrls, depth) {
    if (!root || !root.querySelectorAll || (depth || 0) > 4) return;
    var nodes = root.querySelectorAll("a[href], area[href], form[action], [data-href], [data-url], [routerlink]");
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var raw = el.getAttribute("href") || el.getAttribute("action") || el.getAttribute("data-href")
        || el.getAttribute("data-url") || el.getAttribute("routerlink");
      if (!raw || raw.startsWith("javascript:") || raw.startsWith("mailto:") || raw.startsWith("tel:") || raw.startsWith("data:")) continue;
      try {
        var abs = new URL(raw, location.href).href;
        if (abs.indexOf(location.origin) === 0) outUrls.push(abs);
      } catch (e) {}
    }
    var iframes = root.querySelectorAll("iframe, frame");
    for (var k = 0; k < iframes.length; k++) {
      try {
        if (iframes[k].contentDocument) discoverLinks(iframes[k].contentDocument, outUrls, (depth || 0) + 1);
      } catch (e) {}
    }
  }

  // ---- Smart Config-Driven Form, Date, RichText, Table Cell & File Filler ----

  function findInputContextText(inp) {
    var doc = inp.ownerDocument || document;
    var labelText = "";
    try {
      if (inp.id) {
        var lbl = doc.querySelector("label[for='" + CSS.escape(inp.id) + "']");
        if (lbl) labelText += " " + (lbl.innerText || lbl.textContent || "");
      }
      var wrap = inp.closest && inp.closest("label, .form-group, .field, tr, td");
      if (wrap) labelText += " " + (wrap.innerText || wrap.textContent || "").slice(0, 60);
    } catch (e) {}
    return [
      inp.name || "",
      inp.id || "",
      inp.getAttribute("placeholder") || "",
      inp.getAttribute("aria-label") || "",
      inp.getAttribute("autocomplete") || "",
      inp.getAttribute("data-field") || "",
      labelText
    ].join(" ");
  }

  function matchCustomRule(inp, formFill) {
    var rules = (formFill && formFill.rules) ? formFill.rules : [];
    if (!rules.length) return null;
    var hay = findInputContextText(inp);
    var hayLower = hay.toLowerCase();
    for (var i = 0; i < rules.length; i++) {
      var r = rules[i];
      if (!r || !r.pattern) continue;
      if (r.regex) {
        try {
          if (new RegExp(r.pattern, "i").test(hay)) return r.value;
        } catch (e) {}
      } else {
        if (hayLower.indexOf(String(r.pattern).toLowerCase()) >= 0) return r.value;
      }
    }
    return null;
  }

  function formatDateForPlaceholder(isoDate, placeholder) {
    if (!placeholder || !isoDate) return isoDate;
    var p = String(placeholder).toLowerCase();
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate);
    if (!m) return isoDate;
    if (p.indexOf("dd/mm/yyyy") >= 0 || p.indexOf("ng/th") >= 0) return m[3] + "/" + m[2] + "/" + m[1];
    if (p.indexOf("mm/dd/yyyy") >= 0) return m[2] + "/" + m[3] + "/" + m[1];
    if (p.indexOf("dd-mm-yyyy") >= 0) return m[3] + "-" + m[2] + "-" + m[1];
    return isoDate;
  }

  function resolveValueForInput(inp, formFill) {
    var cfg = formFill || {};
    var defText = cfg.text !== undefined ? String(cfg.text) : "TEXT";

    var ruleMatch = matchCustomRule(inp, cfg);
    if (ruleMatch !== null) return ruleMatch;

    var tag = inp.tagName;
    if (tag === "TEXTAREA") return cfg.textarea !== undefined ? String(cfg.textarea) : defText;

    var type = (inp.type || "text").toLowerCase();
    var ph = inp.getAttribute("placeholder") || "";
    var hay = findInputContextText(inp).toLowerCase();

    if (type === "date") return cfg.date || "2026-01-01";
    if (type === "time") return cfg.time || "09:00";
    if (type === "datetime-local") return cfg["datetime-local"] || "2026-01-01T09:00";
    if (type === "month") return cfg.month || "2026-01";
    if (type === "week") return cfg.week || "2026-W01";
    if (type === "color") return cfg.color || "#000000";
    if (type === "email") return cfg.email || "text@example.com";
    if (type === "password") return cfg.password || "TEXT@123456";
    if (type === "tel") return cfg.tel || "0900000000";
    if (type === "url") return cfg.url || "https://example.com";
    if (type === "number" || type === "range") {
      var n = cfg.number || "1";
      if (inp.min !== undefined && inp.min !== "" && Number(n) < Number(inp.min)) n = inp.min;
      if (inp.max !== undefined && inp.max !== "" && Number(n) > Number(inp.max)) n = inp.max;
      return String(n);
    }
    if (type === "search" || hay.indexOf("search") >= 0 || hay.indexOf("tìm") >= 0) {
      return cfg.search !== undefined ? String(cfg.search) : defText;
    }

    // Respect datepickers implemented as <input type="text">
    if (/dd\/mm\/yyyy|mm\/dd\/yyyy|yyyy-mm-dd|dd-mm-yyyy/i.test(ph)) {
      return formatDateForPlaceholder(cfg.date || "2026-01-01", ph);
    }
    if (/\b(email|e-mail)\b/.test(hay)) return cfg.email || "text@example.com";
    if (/\b(phone|tel|mobile|sdt|điện thoại)\b/.test(hay)) return cfg.tel || "0900000000";
    if (/\b(website|homepage)\b/.test(hay)) return cfg.url || "https://example.com";

    return defText;
  }

  function setNativeInputValue(input, val) {
    try {
      var doc = input.ownerDocument || document;
      var view = doc.defaultView || window;
      input.dispatchEvent(new Event("focus", { bubbles: true }));
      var proto = input.tagName === "TEXTAREA" ? view.HTMLTextAreaElement.prototype : view.HTMLInputElement.prototype;
      var desc = Object.getOwnPropertyDescriptor(proto, "value");
      if (desc && desc.set) desc.set.call(input, val);
      else input.value = val;
      if (input._valueTracker) {
        try { input._valueTracker.setValue(""); } catch (e) {}
      }
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
      input.dispatchEvent(new Event("blur", { bubbles: true }));
    } catch (e) {}
  }

  function setEditableText(el, val) {
    try {
      el.dispatchEvent(new Event("focus", { bubbles: true }));
      el.textContent = val;
      el.dispatchEvent(new Event("input", { bubbles: true }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
      el.dispatchEvent(new Event("blur", { bubbles: true }));
    } catch (e) {}
  }

  function fillScope(scope, formFill) {
    if (!scope || !scope.querySelectorAll) return;
    var overwrite = !!(formFill && formFill.overwrite);

    // 1. Standard inputs / textareas / selects / files
    var inputs = scope.querySelectorAll("input, textarea, select");
    for (var i = 0; i < inputs.length; i++) {
      var inp = inputs[i];
      if (inp.disabled || inp.readOnly || !isVisible(inp)) continue;
      if (inp.closest && inp.closest("[data-myth-ui='1']")) continue;

      var tag = inp.tagName;
      if (tag === "SELECT") {
        if (inp.options && inp.options.length > 0 && (!inp.value || inp.selectedIndex === 0 || overwrite)) {
          for (var o = 0; o < inp.options.length; o++) {
            if (!inp.options[o].disabled && inp.options[o].value !== "") {
              inp.selectedIndex = o;
              inp.dispatchEvent(new Event("change", { bubbles: true }));
              break;
            }
          }
        }
        continue;
      }

      var type = (inp.type || "text").toLowerCase();
      if (type === "hidden" || type === "submit" || type === "button" || type === "reset" || type === "image") continue;

      if (type === "file") {
        if (formFill && formFill.uploadFiles && (!inp.files || inp.files.length === 0)) {
          try {
            var dt = new DataTransfer();
            var fname = formFill.fileName || "TEXT.txt";
            var fcontent = formFill.fileContent || "TEXT";
            dt.items.add(new File([fcontent], fname, { type: "text/plain" }));
            inp.files = dt.files;
            inp.dispatchEvent(new Event("change", { bubbles: true }));
          } catch (e) {}
        }
        continue;
      }

      if (type === "checkbox" || type === "radio") {
        if ((inp.required || type === "radio") && !inp.checked) {
          inp.checked = true;
          inp.dispatchEvent(new Event("change", { bubbles: true }));
        }
        continue;
      }

      if (!overwrite && inp.value && String(inp.value).trim() !== "") continue;
      setNativeInputValue(inp, resolveValueForInput(inp, formFill));
    }

    // 2. ContentEditable / RichText / Editable Table Cells / Gridcells
    var richVal = (formFill && formFill.richtext !== undefined) ? String(formFill.richtext) : ((formFill && formFill.text) || "TEXT");
    var editables = scope.querySelectorAll("[contenteditable='true'], [contenteditable=''], [role='textbox'], td[contenteditable], [role='gridcell'][contenteditable]");
    for (var j = 0; j < editables.length; j++) {
      var ed = editables[j];
      if (!isVisible(ed)) continue;
      if (ed.tagName === "INPUT" || ed.tagName === "TEXTAREA") continue;
      var cur = String(ed.innerText || ed.textContent || "").trim();
      if (!overwrite && cur !== "") continue;
      setEditableText(ed, richVal);
    }
  }

  function autoFillNearestForm(triggerEl, formFill) {
    var doc = triggerEl.ownerDocument || document;
    var scope = (triggerEl.closest && triggerEl.closest("form,[role='dialog'],.modal,table,section,main")) || doc.body;
    fillScope(scope, formFill);
  }

  async function hoverDropdownTriggers(root, depth) {
    if (!root || !root.querySelectorAll || (depth || 0) > 3) return;
    try {
      var triggers = root.querySelectorAll("[aria-haspopup='true'], nav li, .dropdown-toggle, [data-bs-toggle='dropdown']");
      var maxHover = Math.min(triggers.length, 25);
      for (var i = 0; i < maxHover; i++) {
        var t = triggers[i];
        if (!isVisible(t)) continue;
        t.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
        t.dispatchEvent(new MouseEvent("mouseover", { bubbles: true }));
        await sleep(60);
      }
      var iframes = root.querySelectorAll("iframe, frame");
      for (var k = 0; k < iframes.length; k++) {
        try {
          if (iframes[k].contentDocument) await hoverDropdownTriggers(iframes[k].contentDocument, (depth || 0) + 1);
        } catch (e) {}
      }
    } catch (e) {}
  }

  async function performLazyScrollSweep() {
    try {
      var h = Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0);
      if (h > window.innerHeight + 100) {
        window.scrollTo(0, Math.floor(h * 0.5));
        await sleep(220);
        window.scrollTo(0, h);
        await sleep(250);
        window.scrollTo(0, 0);
        await sleep(150);
      }
    } catch (e) {}
  }

  function triggerElementAction(targetEl, targetInfo) {
    var view = getDocView(targetEl);
    if (targetInfo.kind === "select" && targetEl.tagName === "SELECT" && targetEl.options && targetEl.options.length > 1) {
      var nextIdx = (targetEl.selectedIndex + 1) % targetEl.options.length;
      if (targetEl.options[nextIdx].disabled || targetEl.options[nextIdx].value === "") {
        for (var o = 0; o < targetEl.options.length; o++) {
          if (o !== targetEl.selectedIndex && !targetEl.options[o].disabled && targetEl.options[o].value !== "") {
            nextIdx = o;
            break;
          }
        }
      }
      targetEl.selectedIndex = nextIdx;
      targetEl.dispatchEvent(new Event("change", { bubbles: true }));
      return;
    }

    var opts = { bubbles: true, cancelable: true, view: view };
    try {
      if (view.PointerEvent) targetEl.dispatchEvent(new view.PointerEvent("pointerdown", opts));
      targetEl.dispatchEvent(new view.MouseEvent("mousedown", opts));
      if (view.PointerEvent) targetEl.dispatchEvent(new view.PointerEvent("pointerup", opts));
      targetEl.dispatchEvent(new view.MouseEvent("mouseup", opts));
    } catch (e) {}
    targetEl.click();
  }

  async function runCrawlPass() {
    if (runningLoop) return;
    runningLoop = true;
    installCrawlGuards();
    try {
      var state = await apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href));
      currentConfig = state;
      if (state.status !== "RUNNING" || !state.allowAutoRequests) {
        updateBadge(state.status === "RUNNING" ? "CHỜ CHO PHÉP (Thủ công)" : state.status, "#475569");
        return;
      }

      updateBadge("Đang quét " + location.pathname, "#0f766e");
      var pageLoadPath = formatActionPath(location.href, "page load", "", state);
      await apiCall(CONTROL + "/context", "POST", {
        actionPath: pageLoadPath,
        kind: "page load",
        label: "",
        pageUrl: location.href
      });
      await waitForNetworkSettle(650, 3000);

      await hoverDropdownTriggers(document, 0);
      if (state.deepCrawlMode) {
        await performLazyScrollSweep();
      }
      if (state.autoFillForms) {
        fillScope(document.body, state.formFill);
      }

      var links = [];
      discoverLinks(document, links, 0);
      await apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: links, skipped: [] });

      var clickedSet = new Set(state.clickedSignatures || []);
      var actionsDone = 0;
      var maxActions = state.maxActionsPerPage || 300;
      var lazySweepDone = false;

      while (actionsDone < maxActions) {
        var freshState = await apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href));
        currentConfig = freshState;
        if (freshState.status !== "RUNNING" || !freshState.allowAutoRequests) {
          updateBadge(!freshState.allowAutoRequests ? "ĐÃ KHÓA TỰ GỬI" : freshState.status, "#b45309");
          return;
        }

        var items = [];
        collectAllInteractive(document, items, new WeakSet(), 0, "");
        var skippedBatch = [];
        var targetItem = null;
        var targetInfo = null;

        for (var i = 0; i < items.length; i++) {
          var el = items[i].el;
          var fp = items[i].framePrefix;
          var info = extractElementInfo(el, i, fp);
          if (clickedSet.has(info.signature)) continue;

          if (freshState.dangerGuardEnabled) {
            var badKw = isDangerousElement(el, info, freshState.dangerKeywords);
            if (badKw) {
              clickedSet.add(info.signature);
              var skippedPath = formatActionPath(location.href, info.kind, info.label, freshState);
              skippedBatch.push({
                actionPath: skippedPath,
                reason: "Danger Guard: " + badKw,
                tag: info.tag
              });
              await apiCall(CONTROL + "/done_action", "POST", { pageUrl: location.href, signature: info.signature });
              continue;
            }
          }
          targetItem = items[i];
          targetInfo = info;
          break;
        }

        if (skippedBatch.length > 0) {
          await apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: [], skipped: skippedBatch });
        }

        if (!targetItem) {
          if (!lazySweepDone && freshState.deepCrawlMode) {
            lazySweepDone = true;
            await performLazyScrollSweep();
            await waitForNetworkSettle(400, 2000);
            continue;
          }
          break;
        }

        var targetEl = targetItem.el;
        clickedSet.add(targetInfo.signature);
        var beforeUrl = location.href;
        var actionLabel = targetItem.framePrefix ? ("[" + targetItem.framePrefix + "] " + targetInfo.label) : targetInfo.label;
        var actionPath = formatActionPath(beforeUrl, targetInfo.kind, actionLabel, freshState);

        // Queue external same-origin <a href> navigation for BFS without aborting current page's buttons
        if (targetInfo.kind === "link" && targetEl.getAttribute("href")) {
          var href = targetEl.getAttribute("href");
          if (href && !href.startsWith("#") && !href.startsWith("javascript:")) {
            try {
              var dest = new URL(href, beforeUrl).href;
              if (dest.indexOf(location.origin) === 0 && dest !== beforeUrl) {
                await apiCall(CONTROL + "/discovered", "POST", { pageUrl: beforeUrl, urls: [dest], skipped: [] });
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
        await apiCall(CONTROL + "/context", "POST", {
          actionPath: actionPath,
          kind: targetInfo.kind,
          label: actionLabel,
          pageUrl: beforeUrl
        });

        if (freshState.autoFillForms) {
          autoFillNearestForm(targetEl, freshState.formFill);
        }

        try {
          targetEl.scrollIntoView({ block: "center", inline: "center" });
        } catch (e) {}

        try {
          triggerElementAction(targetEl, targetInfo);
        } catch (e) {}

        await waitForNetworkSettle(freshState.actionWaitMs || 550, 3200);

        var newLinks = [];
        discoverLinks(document, newLinks, 0);
        if (newLinks.length > 0) {
          await apiCall(CONTROL + "/discovered", "POST", { pageUrl: beforeUrl, urls: newLinks, skipped: [] });
        }

        await apiCall(CONTROL + "/done_action", "POST", { pageUrl: beforeUrl, signature: targetInfo.signature });
        actionsDone++;

        if (location.href !== beforeUrl) {
          await apiCall(CONTROL + "/discovered", "POST", { pageUrl: beforeUrl, urls: [location.href], skipped: [] });
          try {
            history.back();
            await sleep(480);
            if (location.href !== beforeUrl) {
              location.href = beforeUrl;
              return;
            }
          } catch (e) {}
        }
      }

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

  var trackedDocs = new WeakSet();
  var lastManualPageUrl = "";
  var lastManualActionAt = 0;

  function findInteractiveAncestor(node) {
    if (!node || node.nodeType !== 1) return null;
    if (node.closest) {
      if (node.closest("[data-myth-ui='1']")) return null;
      var match = node.closest("button, a[href], a, select, input[type='submit'], input[type='button'], input[type='checkbox'], input[type='radio'], [role='button'], [role='tab'], [role='menuitem'], [role='link'], [data-myth-clickable='1'], form");
      if (match) return match;
    }
    return node;
  }

  function postManualContext(el, evType, framePrefix) {
    try {
      if (!currentConfig || currentConfig.status === "STOPPED") return;
      if (currentConfig.status === "RUNNING" && currentConfig.allowAutoRequests) return;
      var now = Date.now();
      if (now - lastManualActionAt < 80) return;
      lastManualActionAt = now;

      var targetEl = findInteractiveAncestor(el);
      if (!targetEl) return;
      var info = extractElementInfo(targetEl, 0, framePrefix || "");
      var label = framePrefix ? ("[" + framePrefix + "] " + info.label) : info.label;
      var kind = evType === "submit" ? "form" : (evType === "change" && info.tag === "select" ? "select" : info.kind);
      var ap = formatActionPath(location.href, kind, label, currentConfig);
      updateBadge("Thủ công: " + ap, "#0f766e");
      var bodyStr = JSON.stringify({
        actionPath: ap,
        kind: kind,
        label: label,
        pageUrl: location.href
      });
      if (navigator.sendBeacon) {
        try {
          var blob = new Blob([bodyStr], { type: "application/json" });
          navigator.sendBeacon(CONTROL + "/context", blob);
          return;
        } catch (ignored) {}
      }
      topFetch(CONTROL + "/context", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: bodyStr,
        keepalive: true
      }).catch(function () {});
    } catch (e) {}
  }

  function installHumanContextTracker(doc, framePrefix) {
    try {
      if (!doc || trackedDocs.has(doc)) return;
      trackedDocs.add(doc);
      doc.addEventListener("pointerdown", function (ev) {
        if (ev && ev.isTrusted) postManualContext(ev.target, "click", framePrefix);
      }, true);
      doc.addEventListener("submit", function (ev) {
        if (ev && ev.isTrusted) postManualContext(ev.target, "submit", framePrefix);
      }, true);
      doc.addEventListener("change", function (ev) {
        if (ev && ev.isTrusted && ev.target && (ev.target.tagName === "SELECT" || ev.target.type === "checkbox" || ev.target.type === "radio")) {
          postManualContext(ev.target, "change", framePrefix);
        }
      }, true);
      var frames = doc.querySelectorAll ? doc.querySelectorAll("iframe") : [];
      for (var i = 0; i < frames.length; i++) {
        try {
          var idoc = frames[i].contentDocument;
          if (idoc) {
            hookWindowRuntime(frames[i].contentWindow);
            installHumanContextTracker(idoc, "iframe#" + (i + 1));
          }
        } catch (e) {}
      }
    } catch (e) {}
  }

  function handleStatePoll(st) {
    currentConfig = st;
    installHumanContextTracker(document, "");
    if (st.status === "RUNNING" && st.allowAutoRequests) {
      runCrawlPass();
    } else if (st.status === "RUNNING") {
      if (lastManualPageUrl !== location.href) {
        lastManualPageUrl = location.href;
        var pageLoadPath = formatActionPath(location.href, "page load", "", st);
        apiCall(CONTROL + "/context", "POST", {
          actionPath: pageLoadPath,
          kind: "page load",
          label: "",
          pageUrl: location.href
        }).catch(function () {});
      }
      updateBadge("Thủ công (Chưa bật tự gửi): tự bấm trên trang", "#b45309");
    } else {
      updateBadge(st.status === "IDLE" ? "Sẵn sàng (chờ Start trên Burp)" : st.status, "#475569");
    }
  }

  function pollStatusLoop() {
    setInterval(function () {
      if (runningLoop) return;
      apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href))
        .then(handleStatePoll)
        .catch(function (err) {
          if (Date.now() - lastContextErrorAt > 4000) {
            lastContextErrorAt = Date.now();
            updateBadge("Lỗi /__myth_crawl (" + (err && err.message ? err.message : "Proxy?") + ")", "#b91c1c");
          }
        });
    }, 900);
  }

  function boot() {
    pollStatusLoop();
    setTimeout(function () {
      apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href))
        .then(handleStatePoll)
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
