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
  var domMutationEpoch = 0;
  var lastObservedSpaUrl = location.href;
  var spaRouteDirty = false;
  var domObserverStarted = false;

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

      // 3. Track SPA client-side route transitions (pushState / replaceState / popstate / hashchange)
      if (win === window && win.history && !win.history.__mythHooked) {
        win.history.__mythHooked = true;
        var notifySpaNav = function () {
          try {
            var nowHref = win.location.href;
            if (nowHref && nowHref !== lastObservedSpaUrl) {
              lastObservedSpaUrl = nowHref;
              spaRouteDirty = true;
              if (currentConfig && currentConfig.status === "RUNNING") {
                topFetch(CONTROL + "/discovered", {
                  method: "POST",
                  credentials: "same-origin",
                  headers: { "Content-Type": "application/json" },
                  body: JSON.stringify({ pageUrl: nowHref, urls: [nowHref], skipped: [] })
                }).catch(function () {});
              }
            }
          } catch (e) {}
        };
        var origPush = win.history.pushState;
        var origReplace = win.history.replaceState;
        if (typeof origPush === "function") {
          win.history.pushState = function () {
            var r = origPush.apply(this, arguments);
            notifySpaNav();
            return r;
          };
        }
        if (typeof origReplace === "function") {
          win.history.replaceState = function () {
            var r = origReplace.apply(this, arguments);
            notifySpaNav();
            return r;
          };
        }
        win.addEventListener("popstate", notifySpaNav);
        win.addEventListener("hashchange", notifySpaNav);
      }
    } catch (e) {}
  }

  function installDomMutationObserver() {
    if (domObserverStarted || typeof MutationObserver === "undefined") return;
    var root = document.documentElement || document.body;
    if (!root) return;
    domObserverStarted = true;
    try {
      var timer = null;
      var obs = new MutationObserver(function (mutations) {
        for (var i = 0; i < mutations.length; i++) {
          if (mutations[i].addedNodes && mutations[i].addedNodes.length > 0) {
            if (!timer) {
              timer = setTimeout(function () {
                timer = null;
                domMutationEpoch++;
              }, 120);
            }
            break;
          }
        }
      });
      obs.observe(root, { childList: true, subtree: true });
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

  function isAllowedUrl(rawUrl, stateObj) {
    if (!rawUrl || typeof rawUrl !== "string") return false;
    var lower = rawUrl.trim().toLowerCase();
    if (!lower || lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("blob:")
      || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("sms:")
      || lower.startsWith("itms-apps:") || lower.startsWith("market:")) {
      return false;
    }
    try {
      var u = new URL(rawUrl, location.href);
      if (u.protocol !== "http:" && u.protocol !== "https:") return false;
      if (u.origin === location.origin) return true;
      var hosts = (stateObj && Array.isArray(stateObj.allowedHosts)) ? stateObj.allowedHosts : [];
      var h = (u.hostname || "").toLowerCase();
      for (var i = 0; i < hosts.length; i++) {
        var r = String(hosts[i] || "").trim().toLowerCase();
        if (!r) continue;
        if (r === "*") return true;
        if (r.indexOf("*.") === 0) {
          var suffix = r.slice(2);
          if (h === suffix || h.endsWith("." + suffix)) return true;
        } else if (r.charAt(0) === "*" && r.length > 1) {
          var root = r.slice(1);
          if (root.charAt(0) === ".") root = root.slice(1);
          if (h === root || h.endsWith("." + root)) return true;
        } else if (h === r) {
          return true;
        }
      }
      return false;
    } catch (e) {
      return false;
    }
  }

  function postExternalRedirectNote(actionPath, endpointUrl, msg, kind, label) {
    return apiCall(CONTROL + "/note", "POST", {
      actionPath: actionPath || "",
      kind: kind || "",
      label: label || "",
      pageUrl: location.href,
      noteType: "EXTERNAL_REDIRECT",
      severity: "WARN",
      method: "NAV",
      endpoint: endpointUrl || "",
      deferUrl: endpointUrl || "",
      message: msg || ("Chặn điều hướng ngoài host: " + (endpointUrl || ""))
    }).catch(function () {});
  }

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
          if (isAllowedUrl(abs, currentConfig)) {
            apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: [abs], skipped: [] }).catch(function () {});
          } else {
            var curPath = formatActionPath(location.href, "window.open", sanitizeLabel(abs), currentConfig);
            postExternalRedirectNote(curPath, abs, "Đã chặn window.open ngoài host: " + abs, "window.open", sanitizeLabel(abs));
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

  // Hierarchy Action Path formatter keeping original UI casing and step-by-step context
  function sanitizeLabel(raw, maxLen) {
    if (!raw) return "";
    return String(raw)
      .replace(/[\r\n\t]+/g, " ")
      .replace(/>/g, "›")
      .replace(/"/g, "”")
      .replace(/'/g, "’")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, maxLen || 52);
  }

  function kindDisplayLabel(kind) {
    var k = String(kind || "").toLowerCase();
    if (k === "button" || k === "nút") return "button";
    if (k === "link") return "link";
    if (k === "tab") return "tab";
    if (k === "menu") return "menu-item";
    if (k === "select" || k === "danh sách") return "select";
    if (k === "toggle" || k === "công tắc") return "toggle";
    if (k === "search" || k === "ô tìm kiếm") return "search";
    if (k === "form") return "form";
    if (k === "input" || k === "ô nhập") return "input";
    return kind || "button";
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

  function formatActionPath(urlStr, kind, label, stateObj, regionSegments, framePrefix) {
    var normalizeIds = !stateObj || !!stateObj.normalizeRouteIds;
    var rootLabel = (stateObj && stateObj.routeRootLabel) ? String(stateObj.routeRootLabel).trim() : "/";
    if (!rootLabel) rootLabel = "/";
    var parts = [rootLabel].concat(routeSegments(urlStr, normalizeIds));
    if (framePrefix) {
      var fSeg = 'iframe "' + sanitizeLabel(framePrefix, 32) + '"';
      if (parts.indexOf(fSeg) < 0) {
        parts.push(fSeg);
      }
    }
    if (Array.isArray(regionSegments)) {
      for (var i = 0; i < regionSegments.length; i++) {
        var seg = sanitizeLabel(regionSegments[i], 56);
        if (seg && parts.indexOf(seg) < 0) {
          parts.push(seg);
        }
      }
    }
    if (!kind || kind === "page load") {
      parts.push("page load");
    } else if (label) {
      parts.push(kindDisplayLabel(kind) + ' "' + sanitizeLabel(label, 52) + '"');
    } else {
      parts.push(kindDisplayLabel(kind));
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

  function findModalContainer(el) {
    if (!el || !el.closest) return null;
    try {
      var m = el.closest("[role='dialog'], [aria-modal='true'], .modal.show, .modal[style*='block'], dialog[open]");
      return (m && isVisible(m)) ? m : null;
    } catch (e) {
      return null;
    }
  }

  var ATOMIC_INTERACTIVE_SEL = [
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
    "summary"
  ].join(",");

  function isCompositeInteractiveContainer(node) {
    if (!node || !node.querySelectorAll) return false;
    try {
      var tag = (node.tagName || "").toUpperCase();
      var role = (node.getAttribute("role") || "").toLowerCase();
      if (tag === "SELECT" || tag === "INPUT" || tag === "TEXTAREA" || tag === "SUMMARY") {
        return false;
      }
      var isGenericWrapper = (
        tag === "DIV" || tag === "SPAN" || tag === "NAV" || tag === "HEADER" || tag === "FOOTER"
        || tag === "SECTION" || tag === "ARTICLE" || tag === "ASIDE" || tag === "MAIN"
        || tag === "UL" || tag === "OL" || tag === "LI" || tag === "FORM"
        || tag === "TABLE" || tag === "TBODY" || tag === "TR" || tag === "TD" || tag === "TH"
      ) && role !== "button" && role !== "tab" && role !== "menuitem" && role !== "link";

      var inner = node.querySelectorAll(ATOMIC_INTERACTIVE_SEL);
      var visibleInnerCount = 0;
      for (var i = 0; i < inner.length; i++) {
        var ch = inner[i];
        if (ch !== node && isVisible(ch)) {
          visibleInnerCount++;
          if (isGenericWrapper && visibleInnerCount >= 1) {
            return true;
          }
          if (visibleInnerCount >= 2) {
            return true;
          }
        }
      }
    } catch (e) {}
    return false;
  }

  function extractDirectVisibleText(el) {
    if (!el) return "";
    var view = getDocView(el);
    var chunks = [];
    var SKIP_CHILD_TAGS = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, SVG: 1, UL: 1, OL: 1, MENU: 1, SELECT: 1 };

    function walk(node, depth) {
      if (!node || depth > 5 || chunks.join(" ").length > 72) return;
      if (node.nodeType === 3) {
        var t = (node.nodeValue || "").replace(/\s+/g, " ").trim();
        if (t) chunks.push(t);
        return;
      }
      if (node.nodeType !== 1) return;
      if (node !== el) {
        var nt = (node.tagName || "").toUpperCase();
        if (SKIP_CHILD_TAGS[nt]) return;
        if (node.getAttribute && (node.getAttribute("aria-hidden") === "true" || node.getAttribute("role") === "menu" || node.getAttribute("role") === "listbox")) {
          return;
        }
        try {
          var cls = typeof node.className === "string" ? node.className.toLowerCase() : "";
          if (cls.indexOf("dropdown-menu") >= 0 || cls.indexOf("submenu") >= 0) return;
          var cs = view.getComputedStyle(node);
          if (!cs || cs.display === "none" || cs.visibility === "hidden" || cs.opacity === "0") return;
          if ((cs.position === "absolute" || cs.position === "fixed") && cs.pointerEvents === "none") return;
        } catch (e) {}
      }
      var childNodes = node.childNodes;
      for (var i = 0; i < childNodes.length; i++) {
        walk(childNodes[i], depth + 1);
      }
    }

    walk(el, 0);
    return sanitizeLabel(chunks.join(" "), 52);
  }

  function inferIconLabel(el, tag, rawHref) {
    var clueParts = [
      el.getAttribute("data-testid") || "",
      el.getAttribute("data-icon") || "",
      typeof el.className === "string" ? el.className : "",
      el.getAttribute("name") || "",
      el.id || ""
    ];
    try {
      var iconNode = el.querySelector && el.querySelector("svg, i, span[class*='icon'], use");
      if (iconNode) {
        clueParts.push(iconNode.getAttribute("data-icon") || "");
        clueParts.push(iconNode.getAttribute("aria-label") || "");
        clueParts.push(iconNode.getAttribute("href") || iconNode.getAttribute("xlink:href") || "");
        if (typeof iconNode.className === "string") clueParts.push(iconNode.className);
        else if (iconNode.className && iconNode.className.baseVal) clueParts.push(iconNode.className.baseVal);
      }
    } catch (e) {}
    var clue = clueParts.join(" ").toLowerCase();
    var ICON_MAP = [
      [/search|magnif|find/, "Tìm kiếm"],
      [/close|dismiss|cancel|times/, "Đóng"],
      [/menu|hamburger|bars/, "Menu"],
      [/user|profile|account|avatar/, "Tài khoản"],
      [/heart|like|fav/, "Thích"],
      [/share/, "Chia sẻ"],
      [/cart|bag|basket/, "Giỏ hàng"],
      [/prev|arrow[-_]?left|chevron[-_]?left/, "Trước"],
      [/next|arrow[-_]?right|chevron[-_]?right/, "Tiếp"],
      [/play/, "Phát"],
      [/bell|notif/, "Thông báo"],
      [/filter/, "Lọc"],
      [/calendar|date/, "Lịch"],
      [/edit|pencil/, "Sửa"],
      [/trash|delete|remove/, "Xóa"],
      [/plus|add/, "Thêm"],
      [/download/, "Tải xuống"],
      [/app[-_]?store/, "App Store"],
      [/google[-_]?play/, "Google Play"]
    ];
    for (var i = 0; i < ICON_MAP.length; i++) {
      if (ICON_MAP[i][0].test(clue)) return ICON_MAP[i][1];
    }
    if (rawHref && !rawHref.startsWith("#") && !rawHref.startsWith("javascript:")) {
      try {
        var u = new URL(rawHref, location.href);
        var pathParts = (u.pathname || "").split("/").filter(Boolean);
        if (pathParts.length > 0) return sanitizeLabel(pathParts[pathParts.length - 1], 36);
        if (u.hostname) return sanitizeLabel(u.hostname, 36);
      } catch (e) {}
    }
    return "Biểu tượng " + (tag || "nút");
  }

  function cleanContainerHeading(raw, ownLower) {
    var s = sanitizeLabel(raw, 42);
    if (!s || s.length < 2) return "";
    if (ownLower && s.toLowerCase() === ownLower) return "";
    return s;
  }

  function findHeadingAbove(container, el, elRect, ownLower) {
    if (!container) return "";
    try {
      var aria = cleanContainerHeading(
        container.getAttribute && (container.getAttribute("aria-label") || container.getAttribute("data-title")),
        ownLower
      );
      if (aria) return aria;
      var labelledBy = container.getAttribute && container.getAttribute("aria-labelledby");
      if (labelledBy && container.ownerDocument) {
        var lblEl = container.ownerDocument.getElementById(labelledBy);
        if (lblEl) {
          var lt = cleanContainerHeading(extractDirectVisibleText(lblEl), ownLower);
          if (lt) return lt;
        }
      }
      if (container.querySelectorAll) {
        var hList = container.querySelectorAll(
          "h1, h2, h3, h4, h5, legend, .section-title, .block-title, .widget-title, .box-title, .panel-title, .card-title, .card-header, .footer-title, .sidebar-title, .title-box, [role='heading']"
        );
        for (var i = 0; i < hList.length && i < 6; i++) {
          var hd = hList[i];
          if (!hd || hd === el || (hd.contains && hd.contains(el)) || !isVisible(hd)) continue;
          if (elRect) {
            var hr = hd.getBoundingClientRect();
            if (hr.top > elRect.bottom + 8) continue;
          }
          var txt = cleanContainerHeading(extractDirectVisibleText(hd), ownLower);
          if (txt) return txt;
        }
      }
      var prev = container.previousElementSibling;
      if (prev && /^H[1-5]$/.test(prev.tagName || "")) {
        var pt = cleanContainerHeading(extractDirectVisibleText(prev), ownLower);
        if (pt) return pt;
      }
    } catch (e) {}
    return "";
  }

  function findEnclosingLandmark(el, pattern) {
    var cur = el ? el.parentElement : null;
    for (var depth = 0; cur && cur !== document.body && cur !== document.documentElement && depth < 14; depth++) {
      var tag = (cur.tagName || "").toUpperCase();
      var role = (cur.getAttribute && cur.getAttribute("role") || "").toLowerCase();
      var idCls = ((cur.id || "") + " " + (typeof cur.className === "string" ? cur.className : "")).toLowerCase();
      if (pattern === "header") {
        if (tag === "HEADER" || role === "banner") return cur;
        if (/(^|[-_\s])(site-header|main-header|top-header|topbar|top-bar|navbar)($|[-_\s])/.test(idCls)
          || ((/(^|[-_\s])header($|[-_\s])/.test(idCls)) && idCls.indexOf("card-header") < 0 && idCls.indexOf("modal-header") < 0 && idCls.indexOf("panel-header") < 0)) {
          return cur;
        }
      } else if (pattern === "footer") {
        if (tag === "FOOTER" || role === "contentinfo") return cur;
        if (/(^|[-_\s])(site-footer|main-footer|bottom-bar|footer)($|[-_\s])/.test(idCls)
          && idCls.indexOf("card-footer") < 0 && idCls.indexOf("modal-footer") < 0) {
          return cur;
        }
      } else if (pattern === "sidebar") {
        if (tag === "ASIDE" || role === "complementary") return cur;
        if (/(sidebar|side-bar|left-menu|right-menu|menu-left|nav-left|aside-menu)/.test(idCls)) {
          return cur;
        }
      }
      cur = cur.parentElement;
    }
    return null;
  }

  function extractUiRegionSegments(el, ownLabel) {
    var segs = [];
    var ownLower = (ownLabel || "").trim().toLowerCase();
    var seenTexts = {};

    function pushSegment(prefix, title) {
      var clean = sanitizeLabel(title, 36);
      if (!clean) return false;
      if (clean.toLowerCase() === ownLower || seenTexts[clean.toLowerCase()]) return false;
      seenTexts[clean.toLowerCase()] = true;
      segs.push(prefix + ' "' + clean + '"');
      return true;
    }

    try {
      var elRect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;

      // 1. Popup / Modal / Drawer layer
      var modal = findModalContainer(el);
      if (modal) {
        var mTitle = findHeadingAbove(modal, el, elRect, ownLower) || "hộp thoại";
        pushSegment("popup", mTitle);
      }

      // 2. Major Page Section (Header / Footer / Sidebar / Titled Section / Tab Panel / Main)
      var zoneEl = null;
      var headerEl = findEnclosingLandmark(el, "header");
      var footerEl = headerEl ? null : findEnclosingLandmark(el, "footer");
      var sidebarEl = (!headerEl && !footerEl) ? findEnclosingLandmark(el, "sidebar") : null;

      if (headerEl) {
        zoneEl = headerEl;
        pushSegment("section", "đầu trang");
      } else if (footerEl) {
        zoneEl = footerEl;
        pushSegment("section", "chân trang");
      } else if (sidebarEl) {
        zoneEl = sidebarEl;
        var sbTitle = findHeadingAbove(sidebarEl, el, elRect, ownLower);
        pushSegment("section", sbTitle ? ("thanh bên - " + sbTitle) : "thanh bên");
      } else if (el.closest) {
        var tabPane = el.closest("[role='tabpanel'], .tab-pane");
        if (tabPane) {
          zoneEl = tabPane;
          var tabTitle = findHeadingAbove(tabPane, el, elRect, ownLower);
          pushSegment("section", tabTitle ? ("tab " + tabTitle) : "nội dung tab");
        }
        if (!zoneEl) {
          var cur = el.parentElement;
          for (var d = 0; cur && cur !== document.body && cur !== document.documentElement && d < 12; d++) {
            var tg = (cur.tagName || "").toUpperCase();
            var rl = (cur.getAttribute && cur.getAttribute("role") || "").toLowerCase();
            var cs = typeof cur.className === "string" ? cur.className.toLowerCase() : "";
            var isSecCandidate = tg === "SECTION" || rl === "region" || tg === "ARTICLE"
              || /(section|widget|block|module|box|panel|tab-content|news|featured)/.test(cs);
            if (isSecCandidate) {
              var secHeading = findHeadingAbove(cur, el, elRect, ownLower);
              if (secHeading) {
                zoneEl = cur;
                pushSegment("section", secHeading);
                break;
              }
            }
            cur = cur.parentElement;
          }
        }
        if (!zoneEl) {
          var mainEl = el.closest("main, [role='main'], #main, .main-content, #content, .content, .entry-content");
          if (mainEl) {
            zoneEl = mainEl;
            var mainHeading = findHeadingAbove(mainEl, el, elRect, ownLower);
            pushSegment("section", mainHeading || "nội dung chính");
          }
        }
      }

      // 3. Intermediate context inside the section (nested dropdown menu, form, or column/widget/card)
      var boundary = zoneEl || modal || document.body;
      if (el.closest) {
        // 3a. Enclosing dropdown / submenu parent item
        var submenu = el.closest("ul ul, .dropdown-menu, .sub-menu, .submenu, [role='menu']");
        if (submenu && boundary && boundary.contains(submenu)) {
          var parentLi = submenu.closest("li, .dropdown, .menu-item");
          if (parentLi && boundary.contains(parentLi)) {
            var triggerLink = null;
            for (var cIdx = 0; cIdx < parentLi.children.length; cIdx++) {
              var ch = parentLi.children[cIdx];
              var cht = (ch.tagName || "").toUpperCase();
              if (cht === "A" || cht === "BUTTON" || cht === "SPAN") {
                triggerLink = ch;
                break;
              }
            }
            if (triggerLink && triggerLink !== el && (!triggerLink.contains || !triggerLink.contains(el))) {
              pushSegment("menu", extractDirectVisibleText(triggerLink));
            }
          }
        } else {
          var navEl = el.closest("nav[aria-label]");
          if (navEl && boundary && boundary.contains(navEl) && navEl !== zoneEl) {
            pushSegment("menu", navEl.getAttribute("aria-label"));
          }
        }

        // 3b. Enclosing Form
        var formEl = el.closest("form");
        if (formEl && (!boundary || boundary.contains(formEl) || formEl === boundary)) {
          var fName = findHeadingAbove(formEl, el, elRect, ownLower);
          if (!fName) {
            var fMeta = ((formEl.getAttribute("name") || "") + " " + (formEl.id || "") + " " + (typeof formEl.className === "string" ? formEl.className : "")).toLowerCase();
            if (/search|tim[-_]?kiem|find/.test(fMeta) || formEl.querySelector("input[type='search']")) fName = "tìm kiếm";
            else if (/login|sign[-_]?in|dang[-_]?nhap/.test(fMeta)) fName = "đăng nhập";
            else if (/register|sign[-_]?up|dang[-_]?ky/.test(fMeta)) fName = "đăng ký";
            else if (/contact|lien[-_]?he/.test(fMeta)) fName = "liên hệ";
          }
          if (fName) {
            pushSegment("form", fName);
          }
        }

        // 3c. Enclosing column / widget / card block inside the zone
        var subBox = el.closest("article, fieldset, [class*='footer-col'], [class*='widget'], [class*='box'], [class*='card'], [class*='col-']");
        if (subBox && boundary && subBox !== boundary && boundary.contains(subBox)) {
          var subHeading = findHeadingAbove(subBox, el, elRect, ownLower);
          if (subHeading) {
            pushSegment("mục", subHeading);
          }
        }
      }
    } catch (e) {}
    return segs.slice(0, 3);
  }

  function extractElementInfo(el, idx, framePrefix, docPosKey) {
    var tag = (el.tagName || "").toLowerCase();
    var role = (el.getAttribute("role") || "").toLowerCase();
    var type = (el.getAttribute("type") || "").toLowerCase();
    var rawHref = el.getAttribute("href") || "";

    var kind = "button";
    if (tag === "a" || role === "link") kind = "link";
    else if (tag === "select") kind = "select";
    else if (role === "tab") kind = "tab";
    else if (role === "menuitem" || role === "menuitemcheckbox" || role === "menuitemradio") kind = "menu";
    else if (role === "switch" || (tag === "input" && (type === "checkbox" || type === "radio"))) kind = "toggle";
    else if (tag === "input" && (type === "search" || type === "text")) kind = "search";

    var text = extractDirectVisibleText(el);
    if (!text) text = sanitizeLabel(el.getAttribute("aria-label"), 52);
    if (!text && tag === "input" && (type === "submit" || type === "button" || type === "reset")) {
      text = sanitizeLabel(el.value, 52);
    }
    if (!text) text = sanitizeLabel(el.getAttribute("title"), 52);
    if (!text && el.querySelector) {
      var img = el.querySelector("img[alt], svg[aria-label], svg title");
      if (img) {
        text = sanitizeLabel(img.getAttribute("alt") || img.getAttribute("aria-label") || img.textContent, 52);
      }
    }
    if (!text) text = sanitizeLabel(el.getAttribute("placeholder"), 52);
    if (!text) text = sanitizeLabel(el.getAttribute("name"), 44);
    if (!text) text = inferIconLabel(el, tag, rawHref);

    var regionSegments = extractUiRegionSegments(el, text);
    var regionKey = regionSegments.join("|");
    var sig = [framePrefix || "", regionKey, tag, role, type, el.id || "", el.getAttribute("name") || "", rawHref, text, docPosKey || ""].join("|");
    return {
      kind: kind,
      kindLabel: kindDisplayLabel(kind),
      label: text,
      signature: sig,
      tag: tag,
      regionSegments: regionSegments
    };
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
      if (tag === "HTML" || tag === "BODY" || tag === "MAIN" || tag === "SECTION"
        || tag === "HEADER" || tag === "FOOTER" || tag === "NAV" || tag === "ASIDE") {
        return false;
      }
      var rect = node.getBoundingClientRect();
      if (rect.width > view.innerWidth * 0.90 && rect.height > view.innerHeight * 0.65) {
        return false;
      }
      if (isCompositeInteractiveContainer(node)) {
        return false;
      }
      if (clickableNodes.has(node)) return true;
      var cs = view.getComputedStyle(node);
      if (cs && cs.cursor === "pointer") {
        if (node.closest && node.closest("button, a[href], [role='button'], [role='menuitem'], [role='tab']")) {
          return false;
        }
        return true;
      }
    } catch (e) {}
    return false;
  }

  function measureDocCoords(el, frameOffsetX, frameOffsetY) {
    var rect = el.getBoundingClientRect();
    var view = getDocView(el);
    var style = null;
    try { style = view.getComputedStyle(el); } catch (e) {}
    var scrollX = (window.scrollX || document.documentElement.scrollLeft || 0);
    var scrollY = (window.scrollY || document.documentElement.scrollTop || 0);
    var docHeight = Math.max(
      document.body ? document.body.scrollHeight : 0,
      document.documentElement ? document.documentElement.scrollHeight : 0,
      view.innerHeight || 0
    );
    var isFixed = style && style.position === "fixed";
    var fixedAncestor = false;
    if (!isFixed && el.closest) {
      try {
        var cur = el.parentElement;
        for (var k = 0; k < 5 && cur && cur !== document.body; k++) {
          var pcs = view.getComputedStyle(cur);
          if (pcs && pcs.position === "fixed") {
            fixedAncestor = true;
            var frect = cur.getBoundingClientRect();
            if (frect.top >= (view.innerHeight || 800) * 0.65) {
              return {
                docTop: docHeight + rect.top + (frameOffsetY || 0),
                docLeft: rect.left + (frameOffsetX || 0)
              };
            }
            return {
              docTop: Math.max(0, rect.top) + (frameOffsetY || 0),
              docLeft: rect.left + (frameOffsetX || 0)
            };
          }
          cur = cur.parentElement;
        }
      } catch (e) {}
    }
    if (isFixed && !fixedAncestor) {
      if (rect.top >= (view.innerHeight || 800) * 0.65) {
        return { docTop: docHeight + rect.top + (frameOffsetY || 0), docLeft: rect.left + (frameOffsetX || 0) };
      }
      return { docTop: Math.max(0, rect.top) + (frameOffsetY || 0), docLeft: rect.left + (frameOffsetX || 0) };
    }
    return {
      docTop: rect.top + (frameOffsetY || 0) + scrollY,
      docLeft: rect.left + (frameOffsetX || 0) + scrollX
    };
  }

  function collectAllInteractive(root, out, seenEls, frameDepth, framePrefix, frameOffsetX, frameOffsetY) {
    if (!root || !root.querySelectorAll || frameDepth > 4) return;
    var offX = frameOffsetX || 0;
    var offY = frameOffsetY || 0;
    var selector = [
      "button",
      "input[type='submit']",
      "input[type='button']",
      "input[type='image']",
      "input[type='reset']",
      "input[type='search']",
      "[role='searchbox']",
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

    function pushCandidate(el) {
      if (!el || seenEls.has(el) || !isVisible(el)) return;
      if (isCompositeInteractiveContainer(el)) return;
      seenEls.add(el);
      var coords = measureDocCoords(el, offX, offY);
      var inModal = findModalContainer(el) ? 0 : 1;
      out.push({
        el: el,
        framePrefix: framePrefix || "",
        inModal: inModal,
        docTop: coords.docTop,
        docLeft: coords.docLeft,
        rowBand: Math.round(coords.docTop / 16),
        domOrder: out.length
      });
    }

    var nodes = root.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i++) {
      pushCandidate(nodes[i]);
    }

    var view = (root.defaultView) || (root.ownerDocument && root.ownerDocument.defaultView) || window;
    var all = root.querySelectorAll("*");
    var limit = Math.min(all.length, 4000);
    for (var j = 0; j < limit; j++) {
      var node = all[j];
      if (!seenEls.has(node) && isVisible(node) && isReasonablePointerWidget(node, view)) {
        pushCandidate(node);
      }
      if (node.shadowRoot) {
        collectAllInteractive(node.shadowRoot, out, seenEls, frameDepth + 1, framePrefix, offX, offY);
      }
      if (node.tagName === "IFRAME" || node.tagName === "FRAME") {
        try {
          if (node.contentWindow) hookWindowRuntime(node.contentWindow);
          if (node.contentDocument) {
            var fr = node.getBoundingClientRect();
            var fn = node.getAttribute("name") || node.id || ("frame" + frameDepth);
            collectAllInteractive(node.contentDocument, out, seenEls, frameDepth + 1, fn, offX + fr.left, offY + fr.top);
          }
        } catch (e) {}
      }
    }

    if (frameDepth === 0) {
      out.sort(function (a, b) {
        if (a.inModal !== b.inModal) return a.inModal - b.inModal;
        if (a.rowBand !== b.rowBand) return a.rowBand - b.rowBand;
        if (a.docLeft !== b.docLeft) return a.docLeft - b.docLeft;
        if (a.docTop !== b.docTop) return a.docTop - b.docTop;
        return a.domOrder - b.domOrder;
      });
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

  function getGlobalChromeSignature(el, info) {
    try {
      if (!el || !el.closest) return "";
      if (el.closest("[role='dialog'], [aria-modal='true'], .modal, .drawer, main")) return "";
      var chromeEl = el.closest("header, footer, nav, [role='banner'], [role='contentinfo'], [role='navigation']");
      if (!chromeEl) return "";
      var scope = chromeEl.tagName === "FOOTER" || chromeEl.getAttribute("role") === "contentinfo"
        ? "footer"
        : (chromeEl.tagName === "HEADER" || chromeEl.getAttribute("role") === "banner" ? "header" : "nav");
      var hrefPath = "";
      var a = el.tagName === "A" ? el : (el.closest ? el.closest("a[href]") : null);
      if (a) {
        var raw = (a.getAttribute("href") || "").trim();
        if (raw) {
          try { hrefPath = new URL(raw, location.href).pathname; } catch (e) { hrefPath = raw; }
        }
      }
      return [scope, info.kind || "", (info.label || "").toLowerCase(), hrefPath].join("|");
    } catch (e) {
      return "";
    }
  }

  function detectCaptchaOrChallenge() {
    try {
      var u = (location.href || "").toLowerCase();
      if (u.indexOf("/verify/captcha") >= 0 || u.indexOf("/cdn-cgi/challenge") >= 0) {
        return "URL challenge/captcha";
      }
      if (document.querySelector("iframe[src*='captcha'], iframe[src*='recaptcha'], iframe[src*='turnstile'], .g-recaptcha, #cf-challenge-running")) {
        return "Widget CAPTCHA/Turnstile";
      }
    } catch (e) {}
    return "";
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

    if (targetEl.tagName === "INPUT" && ((targetEl.type || "").toLowerCase() === "search" || targetEl.getAttribute("role") === "searchbox")) {
      try {
        targetEl.focus();
        var val = (currentConfig && currentConfig.formFill && currentConfig.formFill.search) ? currentConfig.formFill.search : "TEXT";
        if (!targetEl.value) {
          setNativeInputValue(targetEl, val);
        }
        var kev = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
        targetEl.dispatchEvent(new view.KeyboardEvent("keydown", kev));
        targetEl.dispatchEvent(new view.KeyboardEvent("keypress", kev));
        targetEl.dispatchEvent(new view.KeyboardEvent("keyup", kev));
        if (targetEl.form && typeof targetEl.form.requestSubmit === "function") {
          targetEl.form.requestSubmit();
        }
        return;
      } catch (e) {}
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
    installDomMutationObserver();
    try {
      var state = await apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href));
      currentConfig = state;
      if (state.status !== "RUNNING" || !state.allowAutoRequests) {
        updateBadge(state.status === "RUNNING" ? "CHỜ CHO PHÉP (Thủ công)" : state.status, "#475569");
        return;
      }

      var captchaReason = detectCaptchaOrChallenge();
      if (captchaReason) {
        updateBadge("Phát hiện CAPTCHA/Xác minh - chờ xử lý tay", "#b91c1c");
        await apiCall(CONTROL + "/note", "POST", {
          actionPath: formatActionPath(location.href, "page load", "", state),
          kind: "page load",
          label: "",
          pageUrl: location.href,
          noteType: "REDIRECT_DEFERRED",
          severity: "WARN",
          method: "NAV",
          endpoint: location.href,
          message: "Tạm dừng tự click do phát hiện " + captchaReason + " tại " + location.href
        }).catch(function () {});
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
      var globalClickedSet = new Set(state.globalClickedSignatures || []);
      var actionsDone = 0;
      var maxActions = state.maxActionsPerPage || 300;
      var lazySweepDone = false;
      var passRouteUrl = location.href;

      while (actionsDone < maxActions) {
        if (spaRouteDirty && location.href !== passRouteUrl) {
          spaRouteDirty = false;
          passRouteUrl = location.href;
          var spaLoadPath = formatActionPath(passRouteUrl, "page load", "", currentConfig);
          await apiCall(CONTROL + "/context", "POST", {
            actionPath: spaLoadPath,
            kind: "page load",
            label: "",
            pageUrl: passRouteUrl
          }).catch(function () {});
        }
        var freshState = await apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href));
        currentConfig = freshState;
        if (freshState.status !== "RUNNING" || !freshState.allowAutoRequests) {
          updateBadge(!freshState.allowAutoRequests ? "ĐÃ KHÓA TỰ GỬI" : freshState.status, "#b45309");
          return;
        }
        if (Array.isArray(freshState.globalClickedSignatures)) {
          for (var gIdx = 0; gIdx < freshState.globalClickedSignatures.length; gIdx++) {
            globalClickedSet.add(freshState.globalClickedSignatures[gIdx]);
          }
        }

        var items = [];
        collectAllInteractive(document, items, new WeakSet(), 0, "", 0, 0);
        var skippedBatch = [];
        var targetItem = null;
        var targetInfo = null;
        var targetGlobalSig = "";

        for (var i = 0; i < items.length; i++) {
          var item = items[i];
          var el = item.el;
          var fp = item.framePrefix;
          var posKey = Math.round((item.docTop || 0) / 32) + "x" + Math.round((item.docLeft || 0) / 32);
          var info = extractElementInfo(el, i, fp, posKey);
          if (clickedSet.has(info.signature)) continue;

          var gSig = getGlobalChromeSignature(el, info);
          if (gSig && globalClickedSet.has(gSig)) {
            clickedSet.add(info.signature);
            continue;
          }

          if (freshState.dangerGuardEnabled) {
            var badKw = isDangerousElement(el, info, freshState.dangerKeywords);
            if (badKw) {
              clickedSet.add(info.signature);
              var skippedPath = formatActionPath(location.href, info.kindLabel, info.label, freshState, info.regionSegments, item.framePrefix);
              skippedBatch.push({
                actionPath: skippedPath,
                reason: "Danger Guard: " + badKw,
                tag: info.tag
              });
              await apiCall(CONTROL + "/done_action", "POST", { pageUrl: location.href, signature: info.signature });
              continue;
            }
          }
          targetItem = item;
          targetInfo = info;
          targetGlobalSig = gSig;
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
        var actionLabel = targetInfo.label;
        var actionPath = formatActionPath(beforeUrl, targetInfo.kindLabel, actionLabel, freshState, targetInfo.regionSegments, targetItem.framePrefix);

        // Guard against external / non-HTTP links BEFORE clicking them (e.g., App Store / Google Play / social / mailto)
        var linkEl = (targetEl.tagName === "A" || (targetEl.getAttribute && targetEl.getAttribute("href")))
          ? targetEl : (targetEl.closest ? targetEl.closest("a[href]") : null);
        var rawHref = linkEl && linkEl.getAttribute ? (linkEl.getAttribute("href") || "") : "";
        if (!rawHref && targetEl.tagName === "FORM" && targetEl.getAttribute) {
          rawHref = targetEl.getAttribute("action") || "";
        }
        var trimmedHref = rawHref.trim();
        if (trimmedHref && !trimmedHref.startsWith("#") && !trimmedHref.toLowerCase().startsWith("javascript:")) {
          var hrefLower = trimmedHref.toLowerCase();
          if (hrefLower.startsWith("mailto:") || hrefLower.startsWith("tel:") || hrefLower.startsWith("sms:")
            || hrefLower.startsWith("itms-apps:") || hrefLower.startsWith("market:")) {
            await apiCall(CONTROL + "/context", "POST", {
              actionPath: actionPath,
              kind: targetInfo.kindLabel,
              label: actionLabel,
              pageUrl: beforeUrl
            });
            await postExternalRedirectNote(actionPath, trimmedHref, "Bỏ qua liên kết giao thức ngoài: " + trimmedHref, targetInfo.kindLabel, actionLabel);
            await apiCall(CONTROL + "/done_action", "POST", { pageUrl: beforeUrl, signature: targetInfo.signature });
            actionsDone++;
            continue;
          }
          try {
            var dest = new URL(trimmedHref, beforeUrl).href;
            if (!isAllowedUrl(dest, freshState)) {
              await apiCall(CONTROL + "/context", "POST", {
                actionPath: actionPath,
                kind: targetInfo.kindLabel,
                label: actionLabel,
                pageUrl: beforeUrl
              });
              await postExternalRedirectNote(actionPath, dest, "Đã chặn link trỏ ra ngoài host: " + dest, targetInfo.kindLabel, actionLabel);
              await apiCall(CONTROL + "/done_action", "POST", { pageUrl: beforeUrl, signature: targetInfo.signature });
              actionsDone++;
              continue;
            }
            if (dest !== beforeUrl) {
              await apiCall(CONTROL + "/discovered", "POST", { pageUrl: beforeUrl, urls: [dest], skipped: [] });
              if (targetInfo.kind === "link" && !targetEl.hasAttribute("data-myth-clickable") && !targetEl.hasAttribute("onclick")) {
                await apiCall(CONTROL + "/done_action", "POST", { pageUrl: beforeUrl, signature: targetInfo.signature });
                actionsDone++;
                continue;
              }
            }
          } catch (e) {}
        }

        if (targetEl.getAttribute("target") === "_blank") {
          targetEl.setAttribute("target", "_self");
        }

        updateBadge(actionPath, "#1d4ed8");
        await apiCall(CONTROL + "/context", "POST", {
          actionPath: actionPath,
          kind: targetInfo.kindLabel,
          label: actionLabel,
          pageUrl: beforeUrl
        });

        // Mark clicked BEFORE firing action so synchronous full-page redirects never replay the same button on return
        if (targetGlobalSig) {
          globalClickedSet.add(targetGlobalSig);
        }
        await apiCall(CONTROL + "/done_action", "POST", {
          pageUrl: beforeUrl,
          signature: targetInfo.signature,
          globalSignature: targetGlobalSig || ""
        });
        actionsDone++;

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

        if (location.href !== beforeUrl) {
          var jumpedUrl = location.href;
          if (!isAllowedUrl(jumpedUrl, freshState)) {
            await postExternalRedirectNote(actionPath, jumpedUrl, "Chặn nhảy ra ngoài host: " + jumpedUrl, targetInfo.kindLabel, actionLabel);
            var btExt = await apiCall(CONTROL + "/backtrack", "POST", { url: jumpedUrl, defer: "1", dest: jumpedUrl });
            var retExt = (btExt && btExt.targetUrl) ? btExt.targetUrl : beforeUrl;
            location.replace(retExt);
            return;
          }
          await apiCall(CONTROL + "/discovered", "POST", { pageUrl: beforeUrl, urls: [jumpedUrl], skipped: [] });
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
      var match = node.closest("button, a[href], a, select, input[type='submit'], input[type='button'], input[type='checkbox'], input[type='radio'], [role='button'], [role='tab'], [role='menuitem'], [role='link']");
      if (match) return match;
      var clickable = node.closest("[data-myth-clickable='1'], form");
      if (clickable && !isCompositeInteractiveContainer(clickable)) return clickable;
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
      var info = extractElementInfo(targetEl, 0, framePrefix || "", "");
      var label = info.label;
      var kind = evType === "submit" ? "form" : (evType === "change" && info.tag === "select" ? "select" : info.kind);
      var kindLbl = kindDisplayLabel(kind);
      var ap = formatActionPath(location.href, kindLbl, label, currentConfig, info.regionSegments, framePrefix);
      updateBadge("Thủ công: " + ap, "#0f766e");
      var bodyStr = JSON.stringify({
        actionPath: ap,
        kind: kindLbl,
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
    installDomMutationObserver();
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
