(function () {
  var isTopWindow = false;
  try { isTopWindow = (window.top === window.self); } catch (e) { isTopWindow = false; }
  if (!isTopWindow) return; // Never let cross-origin ads/embeds/iframes hijack top-level crawl loop
  if (window.__MYTH_CRAWLER_LOADED__) return;
  window.__MYTH_CRAWLER_LOADED__ = true;

  var CONTROL = "/__myth_crawl";
  var clickableNodes = new WeakSet();
  var hookedWindows = new WeakSet();
  var shadowRootsMap = new WeakMap();
  var patchedShadowProtos = new WeakSet();
  var origNativeAttachShadow = (window.Element && window.Element.prototype && window.Element.prototype.attachShadow) || null;
  var pendingNet = 0;
  var runningLoop = false;
  var currentConfig = null;
  var activeContextId = 0;
  var lastContextErrorAt = 0;
  var domMutationEpoch = 0;
  var lastDomMutationAt = Date.now();
  var lastObservedSpaUrl = location.href;
  var spaRouteDirty = false;
  var domObserver = null;
  var domObserverStarted = false;
  var swBypassAttempted = false;
  var seenStateSimHashes = new Map(); // generalizedRouteKey -> Array<[hi, lo]>

  var INTERACTIVE_TYPES = {
    click: 1, mousedown: 1, mouseup: 1, pointerdown: 1, pointerup: 1,
    submit: 1, change: 1, touchstart: 1
  };

  function getShadowRoot(el) {
    if (!el) return null;
    try {
      return shadowRootsMap.get(el) || el.shadowRoot || null;
    } catch (e) {
      return null;
    }
  }

  function patchAttachShadow(win) {
    try {
      if (!win || !win.Element || !win.Element.prototype) return;
      var proto = win.Element.prototype;
      if (patchedShadowProtos.has(proto)) return;
      patchedShadowProtos.add(proto);
      var origAttachShadow = proto.attachShadow;
      if (typeof origAttachShadow === "function") {
        proto.attachShadow = function (init) {
          var sr = origAttachShadow.call(this, init);
          try { shadowRootsMap.set(this, sr); } catch (e) {}
          return sr;
        };
      }
    } catch (e) {}
  }

  function getParentAcrossShadow(node) {
    if (!node) return null;
    try {
      if (node.parentElement) return node.parentElement;
      if (node.parentNode) {
        if (node.parentNode.nodeType === 11 && node.parentNode.host) {
          return node.parentNode.host;
        }
        if (node.parentNode.nodeType === 1) return node.parentNode;
      }
      if (node.getRootNode) {
        var root = node.getRootNode();
        if (root && root !== node && root.host) return root.host;
      }
    } catch (e) {}
    return null;
  }

  function closestAcrossShadow(el, selector) {
    var cur = el;
    for (var depth = 0; cur && depth < 40; depth++) {
      try {
        if (cur.nodeType === 1 && cur.matches && cur.matches(selector)) {
          return cur;
        }
      } catch (e) {}
      cur = getParentAcrossShadow(cur);
    }
    return null;
  }

  function containsAcrossShadow(container, target) {
    if (!container || !target) return false;
    try {
      if (container.contains && container.contains(target)) return true;
    } catch (e) {}
    var cur = target;
    for (var depth = 0; cur && depth < 40; depth++) {
      if (cur === container) return true;
      cur = getParentAcrossShadow(cur);
    }
    return false;
  }

  function buildControlHeaders(hasJsonBody) {
    var h = {};
    if (hasJsonBody) h["Content-Type"] = "application/json";
    if (typeof window.__MYTH_TOKEN__ === "string" && window.__MYTH_TOKEN__) {
      h["X-Myth-Token"] = window.__MYTH_TOKEN__;
    }
    return h;
  }

  function controlFetch(path, opts) {
    var reqOpts = opts || {};
    var headers = buildControlHeaders(false);
    if (reqOpts.headers) {
      var keys = Object.keys(reqOpts.headers);
      for (var i = 0; i < keys.length; i++) {
        headers[keys[i]] = reqOpts.headers[keys[i]];
      }
    }
    reqOpts.headers = headers;
    if (!reqOpts.credentials) reqOpts.credentials = "same-origin";
    return topFetch(path, reqOpts);
  }

  function applyServiceWorkerBypassGuard(stateObj) {
    try {
      if (stateObj && stateObj.bypassServiceWorker === false) return;
      if (!navigator.serviceWorker) return;
      if (!swBypassAttempted && stateObj && stateObj.status === "RUNNING") {
        swBypassAttempted = true;
        if (typeof navigator.serviceWorker.getRegistrations === "function") {
          navigator.serviceWorker.getRegistrations().then(function (regs) {
            if (!regs || !regs.length) return;
            for (var i = 0; i < regs.length; i++) {
              try { regs[i].unregister(); } catch (e) {}
            }
          }).catch(function () {});
        }
      }
    } catch (e) {}
  }

  function hookWindowRuntime(win) {
    try {
      if (!win || hookedWindows.has(win)) return;
      hookedWindows.add(win);
      patchAttachShadow(win);

      // 1. Capture framework listeners (Vue, Svelte, Lit, plain JS, etc.) via WeakSet without DOM mutation
      if (win.EventTarget && win.EventTarget.prototype && win.EventTarget.prototype.addEventListener) {
        var origAdd = win.EventTarget.prototype.addEventListener;
        win.EventTarget.prototype.addEventListener = function (type, listener, options) {
          if (type && INTERACTIVE_TYPES[String(type).toLowerCase()]) {
            try {
              if (this && this.nodeType === 1 && this.tagName !== "BODY" && this.tagName !== "HTML"
                && this.id !== "root" && this.id !== "app") {
                clickableNodes.add(this);
              }
            } catch (e) {}
          }
          return origAdd.apply(this, arguments);
        };
      }

      // 2. Service Worker registration guard when active crawl bypass is enabled
      if (win.navigator && win.navigator.serviceWorker && typeof win.navigator.serviceWorker.register === "function") {
        var origSwReg = win.navigator.serviceWorker.register.bind(win.navigator.serviceWorker);
        win.navigator.serviceWorker.register = function () {
          if (currentConfig && currentConfig.status === "RUNNING" && currentConfig.bypassServiceWorker !== false) {
            return Promise.resolve({
              scope: "/",
              active: null,
              installing: null,
              waiting: null,
              unregister: function () { return Promise.resolve(true); },
              update: function () { return Promise.resolve(); },
              addEventListener: function () {},
              removeEventListener: function () {}
            });
          }
          return origSwReg.apply(win.navigator.serviceWorker, arguments);
        };
      }

      // 3. Track active fetch / XHR / sendBeacon / EventSource / WebSocket
      function isProxyHoldHeaderVal(val) {
        if (typeof val !== "string") return false;
        var v = val.trim().toLowerCase();
        return v === "1" || v === "true" || v === "hold_at_proxy";
      }
      function hasProxyHoldMarker(text) {
        if (typeof text !== "string" || !text) return false;
        return text.indexOf("__MYTH_PROXY_HOLD__") >= 0
          || text.indexOf("HOLD_AT_PROXY") >= 0
          || text.indexOf("\"mythProxyHold\":true") >= 0
          || text.indexOf("\"heldAtProxy\":true") >= 0;
      }
      function markProxyHoldVirtual200(urlStr) {
        try {
          win.__mythProxyHoldCount = (win.__mythProxyHoldCount || 0) + 1;
          win.__mythLastProxyHoldAt = Date.now();
          if (typeof updateBadge === "function") {
            updateBadge("Giữ tại Proxy (" + win.__mythProxyHoldCount + " chờ duyệt)", "#0369a1");
          }
        } catch (e) {}
      }

      function isSameOriginApiUrl(rawUrl) {
        if (!rawUrl || typeof rawUrl !== "string") return false;
        if (rawUrl.indexOf(CONTROL + "/") >= 0) return false;
        try {
          var resolved = new URL(rawUrl, win.location.href);
          return resolved.origin === win.location.origin;
        } catch (e) {
          return false;
        }
      }

      function attachMythCtxToFetchArgs(args) {
        if (!activeContextId || !args || !args.length) return args;
        try {
          var first = args[0];
          var urlStr = typeof first === "string"
            ? first
            : (first && typeof first.url === "string" ? first.url : (first instanceof URL ? first.href : ""));
          if (!isSameOriginApiUrl(urlStr)) return args;
          var ctxVal = String(activeContextId);
          var init = args.length > 1 && args[1] && typeof args[1] === "object" ? args[1] : null;
          if (typeof first === "string" || (typeof URL !== "undefined" && first instanceof URL)) {
            var nextInit = {};
            if (init) {
              var ikeys = Object.keys(init);
              for (var k = 0; k < ikeys.length; k++) nextInit[ikeys[k]] = init[ikeys[k]];
            }
            if (win.Headers && init && init.headers instanceof win.Headers) {
              var hCopy = new win.Headers(init.headers);
              if (!hCopy.has("X-Myth-Ctx")) hCopy.set("X-Myth-Ctx", ctxVal);
              nextInit.headers = hCopy;
            } else if (init && Array.isArray(init.headers)) {
              nextInit.headers = init.headers.concat([["X-Myth-Ctx", ctxVal]]);
            } else {
              var hObj = {};
              if (init && init.headers && typeof init.headers === "object") {
                var hk = Object.keys(init.headers);
                for (var hi = 0; hi < hk.length; hi++) hObj[hk[hi]] = init.headers[hk[hi]];
              }
              if (!hObj["X-Myth-Ctx"]) hObj["X-Myth-Ctx"] = ctxVal;
              nextInit.headers = hObj;
            }
            var clonedArgs = Array.prototype.slice.call(args);
            clonedArgs[1] = nextInit;
            return clonedArgs;
          } else if (win.Request && first instanceof win.Request) {
            var reqHeaders = new win.Headers(init && init.headers ? init.headers : first.headers);
            if (!reqHeaders.has("X-Myth-Ctx")) {
              reqHeaders.set("X-Myth-Ctx", ctxVal);
              var reqClone = new win.Request(first, Object.assign({}, init || {}, { headers: reqHeaders }));
              var reqArgs = Array.prototype.slice.call(args);
              reqArgs[0] = reqClone;
              return reqArgs;
            }
          }
        } catch (e) {}
        return args;
      }

      if (win.fetch) {
        var wOrigFetch = win.fetch;
        win.fetch = function (input) {
          var urlStr = typeof input === "string" ? input : (input && input.url ? input.url : "");
          var isControl = urlStr.indexOf(CONTROL + "/") >= 0;
          var callArgs = isControl ? arguments : attachMythCtxToFetchArgs(arguments);
          if (!isControl) pendingNet++;
          return wOrigFetch.apply(this, callArgs).then(
            function (res) {
              if (!isControl) {
                pendingNet = Math.max(0, pendingNet - 1);
                try {
                  if (res && res.status === 200) {
                    var holdHdr = res.headers && typeof res.headers.get === "function"
                      ? res.headers.get("X-Myth-Proxy-Hold")
                      : "";
                    if (isProxyHoldHeaderVal(holdHdr)) {
                      markProxyHoldVirtual200(urlStr);
                    } else if (typeof res.clone === "function") {
                      var ct = (res.headers && res.headers.get("content-type")) || "";
                      if (!ct || ct.indexOf("json") >= 0 || ct.indexOf("text") >= 0) {
                        res.clone().text().then(function (txt) {
                          if (hasProxyHoldMarker(txt)) markProxyHoldVirtual200(urlStr);
                        }).catch(function () {});
                      }
                    }
                  }
                } catch (e) {}
              }
              return res;
            },
            function (err) { if (!isControl) pendingNet = Math.max(0, pendingNet - 1); throw err; }
          );
        };
      }

      if (win.XMLHttpRequest && win.XMLHttpRequest.prototype) {
        var origOpen = win.XMLHttpRequest.prototype.open;
        var origSend = win.XMLHttpRequest.prototype.send;
        win.XMLHttpRequest.prototype.open = function (m, u) {
          this.__mythUrl = u || "";
          this.__mythSameOrigin = isSameOriginApiUrl(String(u || ""));
          return origOpen.apply(this, arguments);
        };
        win.XMLHttpRequest.prototype.send = function () {
          var self = this;
          var isControl = (self.__mythUrl || "").indexOf(CONTROL + "/") >= 0;
          if (!isControl) {
            if (self.__mythSameOrigin && activeContextId > 0) {
              try { self.setRequestHeader("X-Myth-Ctx", String(activeContextId)); } catch (e) {}
            }
            pendingNet++;
            self.addEventListener("loadend", function () {
              pendingNet = Math.max(0, pendingNet - 1);
              try {
                if (self.status === 200) {
                  var holdHdr = typeof self.getResponseHeader === "function"
                    ? self.getResponseHeader("X-Myth-Proxy-Hold")
                    : "";
                  if (isProxyHoldHeaderVal(holdHdr)) {
                    markProxyHoldVirtual200(self.__mythUrl || "");
                  } else if (!self.responseType || self.responseType === "text") {
                    if (hasProxyHoldMarker(self.responseText)) {
                      markProxyHoldVirtual200(self.__mythUrl || "");
                    }
                  } else if (self.responseType === "json" && self.response && typeof self.response === "object") {
                    if (self.response.__mythProxyHold || self.response.mythProxyHold || self.response.heldAtProxy) {
                      markProxyHoldVirtual200(self.__mythUrl || "");
                    }
                  }
                }
              } catch (e) {}
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

      if (typeof win.EventSource === "function" && !win.EventSource.__mythHooked) {
        var OrigES = win.EventSource;
        var WrappedES = function (url, opts) {
          var es = new OrigES(url, opts);
          pendingNet++;
          var settled = false;
          var release = function () {
            if (settled) return;
            settled = true;
            pendingNet = Math.max(0, pendingNet - 1);
          };
          try {
            es.addEventListener("open", release, { once: true });
            es.addEventListener("error", release, { once: true });
          } catch (e) {}
          setTimeout(release, 600);
          return es;
        };
        WrappedES.prototype = OrigES.prototype;
        WrappedES.__mythHooked = true;
        win.EventSource = WrappedES;
      }

      if (win.WebSocket && win.WebSocket.prototype && !win.WebSocket.prototype.__mythHooked) {
        win.WebSocket.prototype.__mythHooked = true;
        var origWsSend = win.WebSocket.prototype.send;
        if (typeof origWsSend === "function") {
          win.WebSocket.prototype.send = function () {
            pendingNet++;
            setTimeout(function () { pendingNet = Math.max(0, pendingNet - 1); }, 250);
            return origWsSend.apply(this, arguments);
          };
        }
      }

      // 4. Track SPA client-side route transitions (pushState / replaceState / popstate / hashchange / Navigation API)
      if (win === window && win.history && !win.history.__mythHooked) {
        win.history.__mythHooked = true;
        var notifySpaNav = function () {
          try {
            var nowHref = win.location.href;
            if (nowHref && nowHref !== lastObservedSpaUrl) {
              lastObservedSpaUrl = nowHref;
              spaRouteDirty = true;
              lastDomMutationAt = Date.now();
              domMutationEpoch++;
              if (currentConfig && currentConfig.status === "RUNNING") {
                controlFetch(CONTROL + "/discovered", {
                  method: "POST",
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
        if (win.navigation && typeof win.navigation.addEventListener === "function") {
          win.navigation.addEventListener("navigatesuccess", notifySpaNav);
          win.navigation.addEventListener("currententrychange", notifySpaNav);
        }
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
      domObserver = new MutationObserver(function (mutations) {
        for (var i = 0; i < mutations.length; i++) {
          var m = mutations[i];
          if ((m.addedNodes && m.addedNodes.length > 0) || (m.removedNodes && m.removedNodes.length > 0)) {
            lastDomMutationAt = Date.now();
            if (!timer) {
              timer = setTimeout(function () {
                timer = null;
                domMutationEpoch++;
                lastDomMutationAt = Date.now();
              }, 80);
            }
            break;
          }
        }
      });
      domObserver.observe(root, { childList: true, subtree: true });
    } catch (e) {}
  }

  function disconnectDomMutationObserver() {
    if (!domObserverStarted) return;
    domObserverStarted = false;
    try {
      if (domObserver) domObserver.disconnect();
    } catch (e) {}
    domObserver = null;
  }

  var topFetch = window.fetch.bind(window);
  hookWindowRuntime(window);

  function apiCall(path, method, payload) {
    var hasBody = payload !== undefined;
    var opts = {
      method: method || "GET",
      credentials: "same-origin",
      headers: buildControlHeaders(hasBody)
    };
    if (hasBody) {
      opts.body = JSON.stringify(payload);
    }
    return topFetch(path, opts).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (data) {
      if (data && typeof data.contextId === "number" && data.contextId > 0) {
        activeContextId = data.contextId;
      }
      return data;
    });
  }

  // Non-blocking dialog guards while crawl is RUNNING
  var origAlert = window.alert;
  var origConfirm = window.confirm;
  var origPrompt = window.prompt;
  var origPrint = window.print;
  var origWindowOpen = window.open;

  function hostMatchesPatternJs(host, pattern) {
    if (!host || !pattern) return false;
    var h = String(host).trim().toLowerCase();
    var r = String(pattern).trim().toLowerCase();
    if (!r) return false;
    if (r === "*") return true;
    if (r.indexOf("*.") === 0) {
      var suffix = r.slice(2);
      return h === suffix || h.endsWith("." + suffix);
    }
    if (r.charAt(0) === "*" && r.length > 1) {
      var root = r.slice(1);
      if (root.charAt(0) === ".") root = root.slice(1);
      return h === root || h.endsWith("." + root);
    }
    return h === r;
  }

  function normalizePathPrefixJs(rawPath) {
    if (!rawPath) return "/";
    var p = String(rawPath).trim();
    var q = p.indexOf("?");
    if (q >= 0) p = p.slice(0, q);
    var hash = p.indexOf("#");
    if (hash >= 0) p = p.slice(0, hash);
    if (!p) return "/";
    if (p.charAt(0) !== "/") p = "/" + p;
    while (p.length > 1 && p.charAt(p.length - 1) === "/") {
      p = p.slice(0, -1);
    }
    return p;
  }

  function pathMatchesPrefixJs(candidatePath, requiredPrefix, wildcardSuffix) {
    var pref = normalizePathPrefixJs(requiredPrefix);
    if (pref === "/") return true;
    var cand = normalizePathPrefixJs(candidatePath);
    if (cand.toLowerCase() === pref.toLowerCase()) return true;
    if (cand.length <= pref.length) return false;
    if (cand.slice(0, pref.length).toLowerCase() !== pref.toLowerCase()) return false;
    if (wildcardSuffix) return true;
    var nextCh = cand.charAt(pref.length);
    if (nextCh === "/") return true;
    if (nextCh === "-" && (cand.charAt(pref.length + 1) === "p" || cand.charAt(pref.length + 1) === "P")
      && cand.length > pref.length + 2) {
      var digitsOnly = true;
      for (var i = pref.length + 2; i < cand.length; i++) {
        var c = cand.charCodeAt(i);
        if (c < 48 || c > 57) {
          digitsOnly = false;
          break;
        }
      }
      if (digitsOnly) return true;
    }
    return false;
  }

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
      var h = (u.hostname || "").toLowerCase();
      var scopeRules = (stateObj && Array.isArray(stateObj.scopeRules)) ? stateObj.scopeRules : [];
      if (scopeRules.length > 0) {
        var rawPath = u.pathname || "/";
        var hashFrag = u.hash ? u.hash.slice(1) : "";
        var spaHashPath = "";
        if (hashFrag.indexOf("/") === 0) spaHashPath = hashFrag;
        else if (hashFrag.indexOf("!/") === 0) spaHashPath = hashFrag.slice(1);
        for (var srIdx = 0; srIdx < scopeRules.length; srIdx++) {
          var rule = scopeRules[srIdx];
          if (!rule || !hostMatchesPatternJs(h, rule.hostPattern)) continue;
          var reqPref = rule.pathPrefix || "/";
          var wild = !!rule.wildcardSuffix;
          if (reqPref === "/" || pathMatchesPrefixJs(rawPath, reqPref, wild)) return true;
          if (spaHashPath && pathMatchesPrefixJs(spaHashPath, reqPref, wild)) return true;
        }
        return false;
      }
      var hosts = (stateObj && Array.isArray(stateObj.allowedHosts)) ? stateObj.allowedHosts : [];
      if (hosts.length > 0) {
        for (var i = 0; i < hosts.length; i++) {
          if (hostMatchesPatternJs(h, hosts[i])) return true;
        }
        return false;
      }
      return u.origin === location.origin;
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
            postExternalRedirectNote(curPath, abs, "Đã chặn window.open ngoài phạm vi crawl: " + abs, "window.open", sanitizeLabel(abs));
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

  async function waitForNetworkSettle(minWaitMs, maxWaitMs, quietWindowMs) {
    var start = Date.now();
    var quietMs = quietWindowMs || 180;
    var capMs = maxWaitMs || 3200;
    await sleep(Math.max(180, minWaitMs || 550));
    while (Date.now() - start < capMs) {
      var domQuiet = (Date.now() - lastDomMutationAt) >= quietMs;
      if (pendingNet <= 0 && domQuiet) {
        await sleep(Math.min(140, quietMs));
        if (pendingNet <= 0 && (Date.now() - lastDomMutationAt) >= quietMs) {
          break;
        }
      }
      await sleep(90);
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

  function getRouteKey(urlStr, stateObj) {
    var normalizeIds = !stateObj || stateObj.normalizeRouteIds !== false;
    var segs = routeSegments(urlStr || location.href, normalizeIds);
    return segs.length === 0 ? "/" : ("/" + segs.join("/"));
  }

  function getGeneralizedSimHashKey(urlStr, stateObj) {
    var rk = getRouteKey(urlStr, stateObj);
    try {
      var u = new URL(urlStr || location.href, location.href);
      var q = u.search ? u.search.slice(1) : "";
      if (q) {
        var normPairs = q.split("&").map(function (p) {
          var eq = p.indexOf("=");
          if (eq < 0) return p;
          var k = p.slice(0, eq);
          var v = p.slice(eq + 1);
          var kl = k.toLowerCase();
          if (kl === "page" || kl === "p" || kl === "offset" || kl === "cursor" || kl === "id" || /^\d+$/.test(v)) {
            return k + "={#}";
          }
          return p;
        });
        return rk + "?" + normPairs.join("&");
      }
    } catch (e) {}
    return rk;
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

  function getDocLocationHref(el) {
    try {
      return (el && el.ownerDocument && el.ownerDocument.location && el.ownerDocument.location.href) || location.href;
    } catch (e) {
      return location.href;
    }
  }

  function isVisibleFast(el) {
    if (!el || el.nodeType !== 1) return false;
    try {
      if (el.disabled || el.getAttribute("aria-disabled") === "true" || el.hidden) return false;
      if (el.getAttribute("data-myth-ui") === "1") return false;
      if (typeof el.checkVisibility === "function") {
        return el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true });
      }
      var view = getDocView(el);
      var style = view.getComputedStyle(el);
      return !!(style && style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0");
    } catch (e) {
      return false;
    }
  }

  function isVisible(el) {
    if (!isVisibleFast(el) || !el.getBoundingClientRect) return false;
    try {
      if (closestAcrossShadow(el, "[data-myth-ui='1']")) return false;
      var rect = el.getBoundingClientRect();
      return rect.width > 1 && rect.height > 1;
    } catch (e) {
      return false;
    }
  }

  var MODAL_SELECTOR = [
    "[role='dialog']",
    "[aria-modal='true']",
    "dialog[open]",
    ".modal.show",
    ".modal[style*='block']",
    ".MuiDialog-root",
    ".MuiDrawer-root",
    ".ant-modal-wrap:not([style*='display: none'])",
    ".ant-drawer-open",
    ".swal2-container",
    "[data-state='open'][role='dialog']"
  ].join(",");

  function findModalContainer(el) {
    if (!el) return null;
    try {
      var m = closestAcrossShadow(el, MODAL_SELECTOR);
      return (m && isVisible(m)) ? m : null;
    } catch (e) {
      return null;
    }
  }

  function hasVisibleModalInDoc(doc) {
    if (!doc || !doc.querySelectorAll) return false;
    try {
      var modals = doc.querySelectorAll(MODAL_SELECTOR);
      for (var i = 0; i < modals.length; i++) {
        if (isVisible(modals[i])) return true;
      }
    } catch (e) {}
    return false;
  }

  function isModalCloseButton(el, info) {
    if (!el) return false;
    var lbl = (info && info.label ? info.label : "").trim().toLowerCase();
    var aria = (el.getAttribute("aria-label") || "").trim().toLowerCase();
    var title = (el.getAttribute("title") || "").trim().toLowerCase();
    var cls = (typeof el.className === "string" ? el.className : "").toLowerCase();
    if (/^(close|cancel|dismiss|đóng|hủy|huỷ|x|×|✕|✖)$/.test(lbl)) return true;
    if (/^(close|cancel|dismiss|đóng|hủy|huỷ)$/.test(aria) || /^(close|cancel|dismiss|đóng|hủy|huỷ)$/.test(title)) return true;
    if (/(^|\s)(btn-close|close|ant-modal-close|ant-drawer-close|swal2-close)(\s|$)/.test(cls)) return true;
    return false;
  }

  var ATOMIC_INTERACTIVE_SEL = [
    "button",
    "input[type='submit']",
    "input[type='button']",
    "input[type='image']",
    "input[type='reset']",
    "input[type='search']",
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
    "[role='combobox']",
    "[role='searchbox']",
    "[aria-haspopup='listbox']",
    "[aria-haspopup='menu']",
    "[aria-expanded]",
    "summary"
  ].join(",");

  function hasFrameworkDelegatedAction(el) {
    if (!el || el.nodeType !== 1) return false;
    try {
      if (el.hasAttribute("x-on:click") || el.hasAttribute("@click") || el.hasAttribute("v-on:click")
        || el.hasAttribute("(click)") || el.hasAttribute("ng-reflect-router-link")
        || el.hasAttribute("data-action") || el.hasAttribute("data-bs-toggle")
        || el.hasAttribute("data-toggle") || el.hasAttribute("hx-trigger")) {
        return true;
      }
      // Vue 3 (_vei) / Vue 2 (__vue__)
      if (el._vei && (el._vei.onClick || el._vei.onMousedown || el._vei.onPointerdown || el._vei.onChange)) {
        return true;
      }
      // React 17/18/19 root-delegated props (__reactProps$<rand> / __reactEvents$<rand>)
      var ownKeys = Object.keys(el);
      for (var i = 0; i < ownKeys.length; i++) {
        var k = ownKeys[i];
        if (k.indexOf("__reactProps$") === 0) {
          var rp = el[k];
          if (rp && (typeof rp.onClick === "function" || typeof rp.onMouseDown === "function"
            || typeof rp.onPointerDown === "function" || typeof rp.onDoubleClick === "function"
            || typeof rp.onSubmit === "function")) {
            return true;
          }
        } else if (k.indexOf("__reactEvents$") === 0 && el[k] && el[k].size > 0) {
          return true;
        }
      }
    } catch (e) {}
    return false;
  }

  function isAtomicInteractiveNode(el) {
    if (!el || el.nodeType !== 1) return false;
    var tag = (el.tagName || "").toUpperCase();
    if (tag === "BUTTON" || tag === "SELECT" || tag === "SUMMARY") return true;
    if (tag === "A" && el.hasAttribute("href")) return true;
    if (tag === "INPUT") {
      var tp = (el.type || "text").toLowerCase();
      if (tp === "submit" || tp === "button" || tp === "image" || tp === "reset" || tp === "search"
        || tp === "checkbox" || tp === "radio") {
        return true;
      }
      if (el.readOnly && isDatepickerInput(el)) return true;
    }
    var role = (el.getAttribute("role") || "").toLowerCase();
    if (role === "button" || role === "link" || role === "menuitem" || role === "menuitemcheckbox"
      || role === "menuitemradio" || role === "tab" || role === "switch" || role === "option"
      || role === "treeitem" || role === "combobox" || role === "searchbox") {
      return true;
    }
    var hasPopup = (el.getAttribute("aria-haspopup") || "").toLowerCase();
    if (hasPopup === "listbox" || hasPopup === "menu" || hasPopup === "dialog" || el.hasAttribute("aria-expanded")) return true;
    if (el.hasAttribute("onclick") || el.hasAttribute("ng-click") || el.hasAttribute("hx-get")
      || el.hasAttribute("hx-post") || el.hasAttribute("hx-put") || el.hasAttribute("hx-delete")
      || el.hasAttribute("hx-patch") || el.hasAttribute("wire:click")
      || el.getAttribute("tabindex") === "0") {
      return true;
    }
    return false;
  }

  function isCompositeInteractiveContainer(node) {
    if (!node || !node.querySelector) return false;
    try {
      var tag = (node.tagName || "").toUpperCase();
      var role = (node.getAttribute("role") || "").toLowerCase();
      if (tag === "BUTTON" || tag === "A" || tag === "SELECT" || tag === "INPUT" || tag === "TEXTAREA" || tag === "SUMMARY") {
        return false;
      }
      if (role === "button" || role === "tab" || role === "menuitem" || role === "link" || role === "combobox") {
        return false;
      }
      var isGenericWrapper = (
        tag === "DIV" || tag === "SPAN" || tag === "NAV" || tag === "HEADER" || tag === "FOOTER"
        || tag === "SECTION" || tag === "ARTICLE" || tag === "ASIDE" || tag === "MAIN"
        || tag === "UL" || tag === "OL" || tag === "LI" || tag === "FORM"
        || tag === "TABLE" || tag === "TBODY" || tag === "TR" || tag === "TD" || tag === "TH"
      );
      if (!isGenericWrapper) return false;
      var firstInner = node.querySelector(ATOMIC_INTERACTIVE_SEL);
      return !!(firstInner && firstInner !== node && isVisibleFast(firstInner));
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
          if (!isVisibleFast(node)) return;
          var cs = view.getComputedStyle(node);
          if (cs && (cs.position === "absolute" || cs.position === "fixed") && cs.pointerEvents === "none") return;
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
      isVolatileId(el.id) ? "" : (el.id || "")
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
        var u = new URL(rawHref, getDocLocationHref(el));
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
      if (labelledBy) {
        var rootNode = (container.getRootNode && container.getRootNode()) || container.ownerDocument;
        var lblEl = rootNode && rootNode.getElementById ? rootNode.getElementById(labelledBy)
          : (container.ownerDocument ? container.ownerDocument.getElementById(labelledBy) : null);
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
          if (!hd || hd === el || containsAcrossShadow(hd, el) || !isVisible(hd)) continue;
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
    var cur = getParentAcrossShadow(el);
    for (var depth = 0; cur && cur.tagName !== "BODY" && cur.tagName !== "HTML" && depth < 14; depth++) {
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
      cur = getParentAcrossShadow(cur);
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

      var modal = findModalContainer(el);
      if (modal) {
        var mTitle = findHeadingAbove(modal, el, elRect, ownLower) || "hộp thoại";
        pushSegment("popup", mTitle);
      }

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
      } else {
        var tabPane = closestAcrossShadow(el, "[role='tabpanel'], .tab-pane");
        if (tabPane) {
          zoneEl = tabPane;
          var tabTitle = findHeadingAbove(tabPane, el, elRect, ownLower);
          pushSegment("section", tabTitle ? ("tab " + tabTitle) : "nội dung tab");
        }
        if (!zoneEl) {
          var cur = getParentAcrossShadow(el);
          for (var d = 0; cur && cur.tagName !== "BODY" && cur.tagName !== "HTML" && d < 12; d++) {
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
            cur = getParentAcrossShadow(cur);
          }
        }
        if (!zoneEl) {
          var mainEl = closestAcrossShadow(el, "main, [role='main'], #main, .main-content, #content, .content, .entry-content");
          if (mainEl) {
            zoneEl = mainEl;
            var mainHeading = findHeadingAbove(mainEl, el, elRect, ownLower);
            pushSegment("section", mainHeading || "nội dung chính");
          }
        }
      }

      var boundary = zoneEl || modal || (el.ownerDocument ? el.ownerDocument.body : document.body);
      var submenu = closestAcrossShadow(el, "ul ul, .dropdown-menu, .sub-menu, .submenu, [role='menu']");
      if (submenu && boundary && containsAcrossShadow(boundary, submenu)) {
        var parentLi = closestAcrossShadow(submenu, "li, .dropdown, .menu-item");
        if (parentLi && boundary && containsAcrossShadow(boundary, parentLi)) {
          var triggerLink = null;
          for (var cIdx = 0; cIdx < parentLi.children.length; cIdx++) {
            var ch = parentLi.children[cIdx];
            var cht = (ch.tagName || "").toUpperCase();
            if (cht === "A" || cht === "BUTTON" || cht === "SPAN") {
              triggerLink = ch;
              break;
            }
          }
          if (triggerLink && triggerLink !== el && !containsAcrossShadow(triggerLink, el)) {
            pushSegment("menu", extractDirectVisibleText(triggerLink));
          }
        }
      } else {
        var navEl = closestAcrossShadow(el, "nav[aria-label]");
        if (navEl && boundary && containsAcrossShadow(boundary, navEl) && navEl !== zoneEl) {
          pushSegment("menu", navEl.getAttribute("aria-label"));
        }
      }

      var formEl = closestAcrossShadow(el, "form");
      if (formEl && (!boundary || containsAcrossShadow(boundary, formEl) || formEl === boundary)) {
        var fName = findHeadingAbove(formEl, el, elRect, ownLower);
        if (!fName) {
          var fMeta = ((formEl.getAttribute("name") || "") + " " + (formEl.id || "") + " " + (typeof formEl.className === "string" ? formEl.className : "")).toLowerCase();
          if (/search|tim[-_]?kiem|find/.test(fMeta) || formEl.querySelector("input[type='search']")) fName = "tìm kiếm";
          else if (/login|sign[-_]?in|dang[-_]?nhap/.test(fMeta)) fName = "đăng nhập";
          else if (/register|sign[-_]?up|dang[-_]?ky/.test(fMeta)) fName = "đăng ký";
          else if (/contact|lien[-_]?he/.test(fMeta)) fName = "liên hệ";
        }
        if (fName) pushSegment("form", fName);
      }

      var subBox = closestAcrossShadow(el, "article, fieldset, [class*='footer-col'], [class*='widget'], [class*='box'], [class*='card'], [class*='col-']");
      if (subBox && boundary && subBox !== boundary && containsAcrossShadow(boundary, subBox)) {
        var subHeading = findHeadingAbove(subBox, el, elRect, ownLower);
        if (subHeading) pushSegment("mục", subHeading);
      }
    } catch (e) {}
    return segs.slice(0, 3);
  }

  function isVolatileId(id) {
    if (!id) return true;
    if (/^:r[0-9a-z]+:$/i.test(id)) return true;
    if (/^(radix|headlessui|mui|rc_select|react-aria|downshift|el-id)-/i.test(id)) return true;
    if (/[-_][0-9a-f]{6,}$/i.test(id) || /[-_]\d{4,}$/.test(id)) return true;
    return false;
  }

  function normalizeSigText(s) {
    if (!s) return "";
    return String(s).replace(/\(\s*\d+\s*\)/g, "(#)").replace(/\b\d{4,}\b/g, "#").trim();
  }

  function buildStructuralPath(el) {
    var segs = [];
    var cur = el;
    for (var depth = 0; cur && cur.nodeType === 1 && depth < 5; depth++) {
      var tag = (cur.tagName || "").toLowerCase();
      if (tag === "body" || tag === "html") break;
      var part = tag;
      var testId = cur.getAttribute && (cur.getAttribute("data-testid") || cur.getAttribute("data-test") || cur.getAttribute("data-cy"));
      if (testId) {
        part += "[dt=" + sanitizeLabel(testId, 28) + "]";
        segs.unshift(part);
        break;
      }
      if (cur.id && !isVolatileId(cur.id)) {
        part += "#" + sanitizeLabel(cur.id, 28);
        segs.unshift(part);
        break;
      }
      var nm = cur.getAttribute && cur.getAttribute("name");
      if (nm) {
        part += "[name=" + sanitizeLabel(nm, 24) + "]";
      } else if (cur.parentNode && cur.parentNode.children) {
        var idx = 0;
        var sibs = cur.parentNode.children;
        for (var i = 0; i < sibs.length; i++) {
          if (sibs[i].tagName === cur.tagName) {
            idx++;
            if (sibs[i] === cur) break;
          }
        }
        if (idx > 1) part += ":" + idx;
      }
      segs.unshift(part);
      cur = getParentAcrossShadow(cur);
    }
    return segs.join(">");
  }

  function escapeCssIdent(s) {
    if (!s) return "";
    if (typeof CSS !== "undefined" && typeof CSS.escape === "function") {
      return CSS.escape(String(s));
    }
    return String(s).replace(/([!"#$%&'()*+,.\/:;<=>?@\[\\\]^`{|}~\s])/g, "\\$1");
  }

  function escapeCssAttrValue(s) {
    return String(s || "").replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  }

  function buildCssSelectorInRoot(el) {
    if (!el || el.nodeType !== 1) return "";
    var segs = [];
    var cur = el;
    for (var depth = 0; cur && cur.nodeType === 1 && depth < 6; depth++) {
      var tag = (cur.tagName || "").toLowerCase();
      if (!tag || tag === "body" || tag === "html") break;

      var testId = cur.getAttribute && (cur.getAttribute("data-testid") || cur.getAttribute("data-test") || cur.getAttribute("data-cy"));
      if (testId) {
        var attrName = cur.getAttribute("data-testid") ? "data-testid" : (cur.getAttribute("data-test") ? "data-test" : "data-cy");
        segs.unshift(tag + "[" + attrName + '="' + escapeCssAttrValue(testId) + '"]');
        break;
      }
      if (cur.id && !isVolatileId(cur.id)) {
        segs.unshift("#" + escapeCssIdent(cur.id));
        break;
      }

      var part = tag;
      var nm = cur.getAttribute && cur.getAttribute("name");
      var aria = cur.getAttribute && cur.getAttribute("aria-label");
      var usedAttr = false;
      if (nm && nm.length <= 48) {
        part += '[name="' + escapeCssAttrValue(nm) + '"]';
        usedAttr = true;
      } else if (aria && aria.length <= 48) {
        part += '[aria-label="' + escapeCssAttrValue(aria) + '"]';
        usedAttr = true;
      } else if (cur === el && tag === "a") {
        var href = cur.getAttribute("href") || "";
        if (href && !href.startsWith("#") && !href.toLowerCase().startsWith("javascript:") && href.length <= 72) {
          part += '[href="' + escapeCssAttrValue(href) + '"]';
          usedAttr = true;
        }
      }

      var parent = cur.parentNode;
      if (parent && parent.children) {
        var sameTagCount = 0;
        var myIdx = 0;
        var sibs = parent.children;
        for (var i = 0; i < sibs.length; i++) {
          if (sibs[i].tagName === cur.tagName) {
            sameTagCount++;
            if (sibs[i] === cur) myIdx = sameTagCount;
          }
        }
        if (sameTagCount > 1 && myIdx > 0 && !usedAttr) {
          part += ":nth-of-type(" + myIdx + ")";
        } else if (sameTagCount > 1 && myIdx > 0 && usedAttr) {
          // Verify uniqueness within parent if siblings share attribute
          var dupAttr = 0;
          try {
            if (parent.querySelectorAll) dupAttr = parent.querySelectorAll(":scope > " + part).length;
          } catch (e) {}
          if (dupAttr > 1) {
            part += ":nth-of-type(" + myIdx + ")";
          }
        }
      }

      segs.unshift(part);
      if (parent && parent.nodeType === 11) {
        break; // Stop at ShadowRoot boundary; outer hosts go into shadowSelectors[]
      }
      cur = cur.parentElement;
    }
    return segs.join(" > ");
  }

  function collectShadowHostSelectors(el) {
    var hosts = [];
    var cur = el;
    for (var depth = 0; cur && depth < 10; depth++) {
      try {
        var root = cur.getRootNode ? cur.getRootNode() : null;
        if (root && root.nodeType === 11 && root.host) {
          var hostSel = buildCssSelectorInRoot(root.host);
          if (hostSel) hosts.unshift(hostSel);
          cur = root.host;
          continue;
        }
      } catch (e) {}
      break;
    }
    return hosts;
  }

  function collectFilledFieldsNear(el) {
    var out = [];
    if (!el) return out;
    try {
      var scope = closestAcrossShadow(el, "form,[role='dialog'],.modal,table,section,main");
      if (!scope || !scope.querySelectorAll) return out;
      var fields = scope.querySelectorAll("input, textarea, select, [contenteditable='true'], [role='textbox']");
      for (var i = 0; i < fields.length && out.length < 12; i++) {
        var f = fields[i];
        if (!f || f === el || !isVisible(f)) continue;
        if (closestAcrossShadow(f, "[data-myth-ui='1']")) continue;
        var tag = (f.tagName || "").toUpperCase();
        var val = "";
        if (tag === "SELECT") {
          if (f.options && f.selectedIndex >= 0 && f.options[f.selectedIndex]) {
            val = String(f.options[f.selectedIndex].text || f.value || "").trim();
          }
        } else if (tag === "INPUT" || tag === "TEXTAREA") {
          var tp = (f.type || "text").toLowerCase();
          if (tp === "hidden" || tp === "submit" || tp === "button" || tp === "reset" || tp === "image") continue;
          if (tp === "checkbox" || tp === "radio") {
            if (!f.checked) continue;
            val = "checked";
          } else {
            val = String(f.value || "").trim();
            if (tp === "password" && val && !(currentConfig && currentConfig.allowAutoRequests)) {
              val = "••••••";
            }
          }
        } else {
          val = String(f.innerText || f.textContent || "").trim();
        }
        if (!val) continue;
        var sel = buildCssSelectorInRoot(f);
        if (!sel) continue;
        var lbl = sanitizeLabel(findInputContextText(f), 40) || sanitizeLabel(f.name || f.id || tag.toLowerCase(), 40);
        out.push({
          selector: sel,
          label: lbl,
          value: val.slice(0, 80)
        });
      }
    } catch (e) {}
    return out;
  }

  function buildStepLocator(el, info, framePrefix) {
    if (!el || el.nodeType !== 1) return null;
    try {
      return {
        cssSelector: buildCssSelectorInRoot(el),
        shadowSelectors: collectShadowHostSelectors(el),
        framePath: framePrefix || "",
        tag: (info && info.tag) ? info.tag : (el.tagName || "").toLowerCase(),
        role: (el.getAttribute && el.getAttribute("role")) || "",
        filledFields: collectFilledFieldsNear(el)
      };
    } catch (e) {
      return null;
    }
  }

  function buildCheapCandidateKey(el, framePrefix, routeKey) {
    var tag = (el.tagName || "").toLowerCase();
    var role = (el.getAttribute("role") || "").toLowerCase();
    var type = (el.getAttribute("type") || "").toLowerCase();
    var stableId = isVolatileId(el.id) ? "" : (el.id || "");
    var testId = el.getAttribute("data-testid") || "";
    var nameAttr = el.getAttribute("name") || "";
    var rawHref = el.getAttribute("href") || "";
    return [routeKey || "", framePrefix || "", tag, role, type, stableId, testId, nameAttr, rawHref, buildStructuralPath(el)].join("|");
  }

  function extractElementInfo(el, framePrefix, routeKey) {
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
    else if (role === "combobox" || (el.getAttribute("aria-haspopup") || "").toLowerCase() === "listbox") kind = "select";

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
    var stableId = isVolatileId(el.id) ? "" : (el.id || "");
    var testId = el.getAttribute("data-testid") || "";
    var structPath = buildStructuralPath(el);
    var normText = normalizeSigText(text);
    var sig = [
      routeKey || "",
      framePrefix || "",
      regionKey,
      tag,
      role,
      type,
      stableId,
      testId,
      el.getAttribute("name") || "",
      rawHref,
      normText,
      structPath
    ].join("|");

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
      var hasListener = clickableNodes.has(node) || hasFrameworkDelegatedAction(node);
      if (!hasListener) {
        var cs = view.getComputedStyle(node);
        if (!cs || cs.cursor !== "pointer") return false;
        if (closestAcrossShadow(node, "button, a[href], [role='button'], [role='menuitem'], [role='tab'], [role='combobox']")) {
          return false;
        }
      }
      var rect = node.getBoundingClientRect();
      if (rect.width <= 1 || rect.height <= 1) return false;
      if (rect.width > view.innerWidth * 0.90 && rect.height > view.innerHeight * 0.65) {
        return false;
      }
      if (isCompositeInteractiveContainer(node)) {
        return false;
      }
      return true;
    } catch (e) {}
    return false;
  }

  function computeExplorationTier(el, inModal, baseHref) {
    try {
      if (inModal === 0) {
        return isModalCloseButton(el, null) ? 5 : 0;
      }
      var tag = (el.tagName || "").toUpperCase();
      var role = (el.getAttribute("role") || "").toLowerCase();
      var hasPopup = (el.getAttribute("aria-haspopup") || "").toLowerCase();
      var bsToggle = (el.getAttribute("data-bs-toggle") || el.getAttribute("data-toggle") || "").toLowerCase();

      // Tier 1: In-page state expanders (Tabs, Accordions, Menus, Modal triggers)
      if (role === "tab" || tag === "SUMMARY"
        || el.getAttribute("aria-expanded") === "false"
        || hasPopup === "menu" || hasPopup === "dialog"
        || bsToggle === "tab" || bsToggle === "pill" || bsToggle === "collapse"
        || bsToggle === "dropdown" || bsToggle === "modal") {
        return 1;
      }

      // Links: distinguish same-route query/hash switches vs cross-route page navigations
      if (tag === "A" || role === "link") {
        var rawHref = (el.getAttribute("href") || "").trim();
        if (!rawHref || rawHref === "#" || rawHref.toLowerCase().indexOf("javascript:") === 0) {
          return 2;
        }
        try {
          var curU = new URL(baseHref || location.href, location.href);
          var dstU = new URL(rawHref, curU.href);
          if (dstU.origin === curU.origin && dstU.pathname === curU.pathname) {
            return 3; // Same-route filter/tab/pagination query
          }
        } catch (e) {}
        return 4; // Cross-route navigation LAST after in-page controls
      }

      // Tier 2: Forms, filters, selects, comboboxes, toggles, and in-page AJAX buttons
      return 2;
    } catch (e) {
      return 2;
    }
  }

  function measureDocCoords(el, frameOffsetX, frameOffsetY) {
    var rect = el.getBoundingClientRect();
    var view = getDocView(el);
    var doc = el.ownerDocument || document;
    var style = null;
    try { style = view.getComputedStyle(el); } catch (e) {}
    var scrollX = view.scrollX || (doc.documentElement ? doc.documentElement.scrollLeft : 0) || 0;
    var scrollY = view.scrollY || (doc.documentElement ? doc.documentElement.scrollTop : 0) || 0;
    var docHeight = Math.max(
      doc.body ? doc.body.scrollHeight : 0,
      doc.documentElement ? doc.documentElement.scrollHeight : 0,
      view.innerHeight || 0
    );
    var isFixed = style && style.position === "fixed";
    var fixedAncestor = false;
    if (!isFixed) {
      try {
        var cur = getParentAcrossShadow(el);
        for (var k = 0; k < 5 && cur && cur.tagName !== "BODY"; k++) {
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
          cur = getParentAcrossShadow(cur);
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

  function collectAllInteractive(root, out, seenEls, frameDepth, shadowDepth, framePrefix, frameOffsetX, frameOffsetY, frameHref, activeModalPresent) {
    if (!root || frameDepth > 4 || shadowDepth > 8) return;
    var doc = root.ownerDocument || (root.nodeType === 9 ? root : document);
    var view = (doc && doc.defaultView) || window;
    var offX = frameOffsetX || 0;
    var offY = frameOffsetY || 0;
    var effectiveModal = activeModalPresent || (frameDepth === 0 && shadowDepth === 0 && hasVisibleModalInDoc(doc));
    var effectiveHref = frameHref || (doc.location ? doc.location.href : location.href);

    function pushCandidate(el) {
      if (!el || seenEls.has(el)) return;
      if (!isVisible(el)) return;
      var modalEl = findModalContainer(el);
      if (effectiveModal && !modalEl) return; // Suppress background elements while modal is active
      if (isCompositeInteractiveContainer(el)) return;
      seenEls.add(el);
      var coords = measureDocCoords(el, offX, offY);
      var mFlag = modalEl ? 0 : 1;
      out.push({
        el: el,
        framePrefix: framePrefix || "",
        frameHref: effectiveHref,
        inModal: mFlag,
        tier: computeExplorationTier(el, mFlag, effectiveHref),
        docTop: coords.docTop,
        docLeft: coords.docLeft,
        rowBand: Math.round(coords.docTop / 16),
        domOrder: out.length
      });
    }

    var startNode = (root.nodeType === 9) ? (root.body || root.documentElement) : root;
    if (!startNode) return;

    var SKIP_SUBTREE = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1 };
    var walker = doc.createTreeWalker(startNode, NodeFilter.SHOW_ELEMENT, {
      acceptNode: function (node) {
        var tag = node.tagName;
        if (SKIP_SUBTREE[tag]) return NodeFilter.FILTER_REJECT;
        if (node.getAttribute && node.getAttribute("data-myth-ui") === "1") return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });

    var count = 0;
    var node = startNode.nodeType === 1 ? startNode : walker.nextNode();
    while (node && count < 25000) {
      count++;
      if (isVisibleFast(node)) {
        if (isAtomicInteractiveNode(node) || isReasonablePointerWidget(node, view)) {
          pushCandidate(node);
        }
      }
      var sr = getShadowRoot(node);
      if (sr) {
        collectAllInteractive(sr, out, seenEls, frameDepth, shadowDepth + 1, framePrefix, offX, offY, effectiveHref, effectiveModal);
      }
      if (node.tagName === "IFRAME" || node.tagName === "FRAME") {
        try {
          if (node.contentWindow) hookWindowRuntime(node.contentWindow);
          if (node.contentDocument) {
            var fr = node.getBoundingClientRect();
            var fn = node.getAttribute("name") || node.id || ("frame" + frameDepth);
            var fHref = (node.contentDocument.location && node.contentDocument.location.href) || effectiveHref;
            collectAllInteractive(node.contentDocument, out, seenEls, frameDepth + 1, 0, fn, offX + fr.left, offY + fr.top, fHref, false);
          }
        } catch (e) {}
      }
      node = walker.nextNode();
    }

    if (frameDepth === 0 && shadowDepth === 0) {
      out.sort(function (a, b) {
        if (a.inModal !== b.inModal) return a.inModal - b.inModal;
        if (a.tier !== b.tier) return a.tier - b.tier;
        if (a.rowBand !== b.rowBand) return a.rowBand - b.rowBand;
        if (a.docLeft !== b.docLeft) return a.docLeft - b.docLeft;
        if (a.docTop !== b.docTop) return a.docTop - b.docTop;
        return a.domOrder - b.domOrder;
      });
    }
  }

  function discoverSpaFrameworkRoutes(outUrls, stateObj) {
    try {
      // Next.js client build manifest
      if (window.__BUILD_MANIFEST && Array.isArray(window.__BUILD_MANIFEST.sortedPages)) {
        var pages = window.__BUILD_MANIFEST.sortedPages;
        for (var i = 0; i < pages.length && i < 200; i++) {
          var p = String(pages[i] || "");
          if (!p || p.indexOf("/_") === 0 || p.indexOf("/api/") === 0 || p.indexOf("[") >= 0) continue;
          var abs = new URL(p, location.origin).href;
          if (isAllowedUrl(abs, stateObj || currentConfig)) outUrls.push(abs);
        }
      }
      // Next.js SSR __NEXT_DATA__ page & query hints
      var nextScript = document.getElementById("__NEXT_DATA__");
      if (nextScript && nextScript.textContent && nextScript.textContent.length < 250000) {
        var parsed = JSON.parse(nextScript.textContent);
        if (parsed && typeof parsed.page === "string" && parsed.page.indexOf("[") < 0 && parsed.page.indexOf("/_") !== 0) {
          var np = new URL(parsed.page, location.origin).href;
          if (isAllowedUrl(np, stateObj || currentConfig)) outUrls.push(np);
        }
      }
    } catch (e) {}
  }

  function discoverLinks(root, outUrls, frameDepth, shadowDepth, stateObj, baseHref) {
    if (!root || (frameDepth || 0) > 4 || (shadowDepth || 0) > 8) return;
    var doc = root.ownerDocument || (root.nodeType === 9 ? root : document);
    var docHref = baseHref || (doc.location ? doc.location.href : location.href);
    var nodes = root.querySelectorAll
      ? root.querySelectorAll("a[href], area[href], form[action], [data-href], [data-url], [routerlink], [data-to]")
      : [];
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var raw = el.getAttribute("href") || el.getAttribute("action") || el.getAttribute("data-href")
        || el.getAttribute("data-url") || el.getAttribute("routerlink") || el.getAttribute("data-to");
      if (!raw) continue;
      var low = raw.trim().toLowerCase();
      if (!low || low.startsWith("javascript:") || low.startsWith("mailto:") || low.startsWith("tel:") || low.startsWith("data:")) continue;
      try {
        var abs = new URL(raw, docHref).href;
        if (isAllowedUrl(abs, stateObj || currentConfig)) outUrls.push(abs);
      } catch (e) {}
    }

    var startNode = (root.nodeType === 9) ? (root.body || root.documentElement) : root;
    if (!startNode) return;
    var walker = doc.createTreeWalker(startNode, NodeFilter.SHOW_ELEMENT, null);
    var n = startNode.nodeType === 1 ? startNode : walker.nextNode();
    var scanned = 0;
    while (n && scanned < 15000) {
      scanned++;
      var sr = getShadowRoot(n);
      if (sr) {
        discoverLinks(sr, outUrls, frameDepth || 0, (shadowDepth || 0) + 1, stateObj, docHref);
      }
      if (n.tagName === "IFRAME" || n.tagName === "FRAME") {
        try {
          if (n.contentDocument) {
            var fHref = (n.contentDocument.location && n.contentDocument.location.href) || docHref;
            discoverLinks(n.contentDocument, outUrls, (frameDepth || 0) + 1, 0, stateObj, fHref);
          }
        } catch (e) {}
      }
      n = walker.nextNode();
    }
  }

  // ---- 64-bit DOM SimHash for SPA Near-Duplicate State Deduplication ----

  function fnv1aPair(str) {
    var h1 = 0x811c9dc5;
    var h2 = 0x01000193;
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      h1 ^= c;
      h1 = Math.imul(h1, 0x01000193);
      h2 ^= (c + (i & 0xff));
      h2 = Math.imul(h2, 0x85ebca6b);
    }
    return [h1 >>> 0, h2 >>> 0];
  }

  function computeDomSimHash(items) {
    var v = new Int32Array(64);
    var count = Math.min(items ? items.length : 0, 200);
    for (var i = 0; i < count; i++) {
      var el = items[i].el;
      if (!el) continue;
      var tag = (el.tagName || "").toLowerCase();
      var role = (el.getAttribute("role") || "").toLowerCase();
      var nm = el.getAttribute("name") || el.getAttribute("data-testid") || "";
      var href = el.getAttribute("href") || "";
      var token = tag + "|" + role + "|" + nm + "|" + href.replace(/\d+/g, "#");
      var pair = fnv1aPair(token);
      var weight = 1;
      for (var b = 0; b < 32; b++) {
        v[b] += ((pair[0] >>> b) & 1) ? weight : -weight;
        v[32 + b] += ((pair[1] >>> b) & 1) ? weight : -weight;
      }
    }
    var hi = 0;
    var lo = 0;
    for (var bit = 0; bit < 32; bit++) {
      if (v[bit] > 0) lo |= (1 << bit);
      if (v[32 + bit] > 0) hi |= (1 << bit);
    }
    return [hi >>> 0, lo >>> 0];
  }

  function hammingDistance64(a, b) {
    var x0 = (a[0] ^ b[0]) >>> 0;
    var x1 = (a[1] ^ b[1]) >>> 0;
    var d = 0;
    while (x0) { x0 &= x0 - 1; d++; }
    while (x1) { x1 &= x1 - 1; d++; }
    return d;
  }

  function isNearDuplicateSpaState(genRouteKey, simHash) {
    var bucket = seenStateSimHashes.get(genRouteKey);
    if (!bucket) return false;
    for (var i = 0; i < bucket.length; i++) {
      if (hammingDistance64(bucket[i], simHash) <= 3) return true;
    }
    return false;
  }

  function recordSpaSimHash(genRouteKey, simHash) {
    var bucket = seenStateSimHashes.get(genRouteKey);
    if (!bucket) {
      bucket = [];
      seenStateSimHashes.set(genRouteKey, bucket);
    }
    if (bucket.length < 32) {
      bucket.push(simHash);
    }
  }

  // ---- Smart Config-Driven Form, Date, RichText, Table Cell & File Filler ----

  function findInputContextText(inp) {
    var root = (inp.getRootNode && inp.getRootNode()) || inp.ownerDocument || document;
    var doc = inp.ownerDocument || document;
    var labelText = "";
    try {
      if (inp.id) {
        var sel = "label[for='" + CSS.escape(inp.id) + "']";
        var lbl = (root && root.querySelector ? root.querySelector(sel) : null) || doc.querySelector(sel);
        if (lbl) labelText += " " + (lbl.innerText || lbl.textContent || "");
      }
      var wrap = closestAcrossShadow(inp, "label, .form-group, .field, tr, td");
      if (wrap) labelText += " " + (wrap.innerText || wrap.textContent || "").slice(0, 60);
    } catch (e) {}
    return [
      inp.name || "",
      isVolatileId(inp.id) ? "" : (inp.id || ""),
      inp.getAttribute("placeholder") || "",
      inp.getAttribute("aria-label") || "",
      inp.getAttribute("autocomplete") || "",
      inp.getAttribute("data-field") || "",
      labelText
    ].join(" ");
  }

  function isDatepickerInput(inp) {
    if (!inp) return false;
    var ph = (inp.getAttribute("placeholder") || "").toLowerCase();
    var cls = (typeof inp.className === "string" ? inp.className : "").toLowerCase();
    var nm = ((inp.name || "") + " " + (inp.getAttribute("aria-label") || "")).toLowerCase();
    if (/dd\/mm\/yyyy|mm\/dd\/yyyy|yyyy-mm-dd|dd-mm-yyyy|date|ngày|calendar/.test(ph + " " + nm)) return true;
    if (/flatpickr|datepicker|ant-picker|mui.*date/.test(cls)) return true;
    return !!closestAcrossShadow(inp, ".ant-picker, .MuiPickersLayout-root, .flatpickr-wrapper, .datepicker");
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

  function resolveLiveIdForInput(inp, hayLower) {
    try {
      var liveIds = currentConfig && currentConfig.liveIds;
      if (!liveIds || typeof liveIds !== "object") return null;
      var rawKey = ((inp.name || "") + " " + (inp.getAttribute("data-field") || "") + " " + (isVolatileId(inp.id) ? "" : (inp.id || ""))).trim().toLowerCase();
      if (!rawKey && !/\b(id|uuid|mã)\b/.test(hayLower)) return null;
      var m = /([a-z0-9]+?)(?:_?id|_?uuid)$/i.exec(inp.name || inp.id || "");
      if (m && m[1]) {
        var base = m[1].toLowerCase();
        if (liveIds[base]) return String(liveIds[base]);
        if (liveIds[base + "s"]) return String(liveIds[base + "s"]);
      }
      if (/^(id|uuid|item_?id|record_?id)$/i.test(inp.name || inp.id || "")) {
        var routeSegs = routeSegments(location.href, true);
        for (var i = routeSegs.length - 1; i >= 0; i--) {
          var rs = routeSegs[i].toLowerCase();
          if (rs !== "{id}" && liveIds[rs]) return String(liveIds[rs]);
        }
      }
    } catch (e) {}
    return null;
  }

  function pickDatalistOption(inp) {
    try {
      var listId = inp.getAttribute && inp.getAttribute("list");
      if (!listId) return null;
      var doc = inp.ownerDocument || document;
      var dl = doc.getElementById(listId);
      if (!dl || !dl.options) return null;
      for (var i = 0; i < dl.options.length; i++) {
        var v = String(dl.options[i].value || "").trim();
        if (v) return v;
      }
    } catch (e) {}
    return null;
  }

  function applyHtml5Constraints(inp, candidateVal) {
    var val = String(candidateVal !== undefined && candidateVal !== null ? candidateVal : "");
    try {
      var pat = inp.getAttribute && inp.getAttribute("pattern");
      if (pat) {
        var rx = new RegExp("^(?:" + pat + ")$");
        if (!rx.test(val)) {
          var fallbacks = ["123456", "0901234567", "100", "2026-01-01", "TEST001", "test@example.com", "550e8400-e29b-41d4-a716-446655440000", "ABC12345"];
          for (var i = 0; i < fallbacks.length; i++) {
            if (rx.test(fallbacks[i])) {
              val = fallbacks[i];
              break;
            }
          }
        }
      }
      var minL = inp.minLength;
      var maxL = inp.maxLength;
      if (typeof minL === "number" && minL > 0 && val.length < minL) {
        var padChar = /^\d+$/.test(val) ? "1" : "a";
        while (val.length < minL) val += padChar;
      }
      if (typeof maxL === "number" && maxL > 0 && val.length > maxL) {
        val = val.slice(0, maxL);
      }
    } catch (e) {}
    return val;
  }

  function resolveValueForInput(inp, formFill, scopePasswordRef) {
    var cfg = formFill || {};
    var defText = cfg.text !== undefined ? String(cfg.text) : "TEXT";

    var ruleMatch = matchCustomRule(inp, cfg);
    if (ruleMatch !== null) return applyHtml5Constraints(inp, ruleMatch);

    var dlVal = pickDatalistOption(inp);
    if (dlVal !== null) return applyHtml5Constraints(inp, dlVal);

    var tag = inp.tagName;
    if (tag === "TEXTAREA") return applyHtml5Constraints(inp, cfg.textarea !== undefined ? String(cfg.textarea) : defText);

    var type = (inp.type || "text").toLowerCase();
    var ph = inp.getAttribute("placeholder") || "";
    var inputMode = (inp.getAttribute("inputmode") || "").toLowerCase();
    var hay = findInputContextText(inp).toLowerCase();

    if (type === "date") return cfg.date || "2026-01-01";
    if (type === "time") return cfg.time || "09:00";
    if (type === "datetime-local") return cfg["datetime-local"] || "2026-01-01T09:00";
    if (type === "month") return cfg.month || "2026-01";
    if (type === "week") return cfg.week || "2026-W01";
    if (type === "color") return cfg.color || "#000000";
    if (type === "email") return cfg.email || "text@example.com";
    if (type === "password") {
      var pwd = cfg.password || "TEXT@123456";
      if (scopePasswordRef) {
        if (/confirm|re[-_]?type|re[-_]?pass|verify|repeat|nhập\s*lại|xác\s*nhận/.test(hay) && scopePasswordRef.primary) {
          return scopePasswordRef.primary;
        }
        if (!scopePasswordRef.primary) scopePasswordRef.primary = pwd;
      }
      return pwd;
    }
    if (type === "tel") return applyHtml5Constraints(inp, cfg.tel || "0901234567");
    if (type === "url") return cfg.url || "https://example.com";
    if (type === "number" || type === "range") {
      var n = cfg.number || "1";
      if (inp.min !== undefined && inp.min !== "" && Number(n) < Number(inp.min)) n = inp.min;
      if (inp.max !== undefined && inp.max !== "" && Number(n) > Number(inp.max)) n = inp.max;
      return String(n);
    }
    if (type === "search" || hay.indexOf("search") >= 0 || hay.indexOf("tìm") >= 0) {
      return applyHtml5Constraints(inp, cfg.search !== undefined ? String(cfg.search) : defText);
    }

    // LiveIdPool foreign-key injection for *_id / uuid fields
    var liveId = resolveLiveIdForInput(inp, hay);
    if (liveId !== null) return applyHtml5Constraints(inp, liveId);

    if (/dd\/mm\/yyyy|mm\/dd\/yyyy|yyyy-mm-dd|dd-mm-yyyy/i.test(ph)) {
      return formatDateForPlaceholder(cfg.date || "2026-01-01", ph);
    }
    if (/\b(email|e-mail)\b/.test(hay)) return cfg.email || "text@example.com";
    if (/\b(phone|tel|mobile|sdt|sđt|điện\s*thoại|di\s*động)\b/.test(hay)) return applyHtml5Constraints(inp, cfg.tel || "0901234567");
    if (/\b(website|homepage|link|url|trang\s*web)\b/.test(hay)) return cfg.url || "https://example.com";

    // Semantic synthesis when default text is generic ("TEXT") so numeric/code validations succeed
    if (defText === "TEXT") {
      if (/\b(otp|pin|passcode|verification\s*code|mã\s*xác\s*thực|mã\s*otp)\b/.test(hay)) {
        return applyHtml5Constraints(inp, "123456");
      }
      if (/\b(cccd|cmnd|citizen|national\s*id|mã\s*số\s*thuế|mst|tax\s*code)\b/.test(hay)) {
        return applyHtml5Constraints(inp, "001099001234");
      }
      if (/\b(year|năm\s*sinh|năm)\b/.test(hay)) {
        return applyHtml5Constraints(inp, "2026");
      }
      if (/\b(price|amount|total|cost|fee|salary|budget|đơn\s*giá|thành\s*tiền|số\s*tiền|giá|chi\s*phí|lương)\b/.test(hay)) {
        return applyHtml5Constraints(inp, "100000");
      }
      if (inputMode === "numeric" || inputMode === "decimal"
        || /\b(qty|quantity|count|age|port|zip|postal|số\s*lượng|tuổi|mã\s*bưu\s*điện)\b/.test(hay)) {
        return applyHtml5Constraints(inp, cfg.number || "1");
      }
      if (/\b(username|user_name|login_id|tài\s*khoản|tên\s*đăng\s*nhập)\b/.test(hay)) {
        return applyHtml5Constraints(inp, "test_user_01");
      }
      if (/\b(code|sku|voucher|coupon|slug|mã)\b/.test(hay)) {
        return applyHtml5Constraints(inp, "CODE001");
      }
    }

    return applyHtml5Constraints(inp, defText);
  }

  function dispatchInputEvent(el, view, val) {
    try {
      el.dispatchEvent(new view.Event("beforeinput", { bubbles: true, cancelable: true }));
    } catch (e) {}
    try {
      if (typeof view.InputEvent === "function") {
        el.dispatchEvent(new view.InputEvent("input", {
          bubbles: true,
          inputType: "insertText",
          data: val !== undefined ? String(val) : ""
        }));
        return;
      }
    } catch (e) {}
    el.dispatchEvent(new view.Event("input", { bubbles: true }));
  }

  function setNativeInputValue(input, val, deferBlur) {
    try {
      var view = getDocView(input);
      input.dispatchEvent(new view.FocusEvent("focusin", { bubbles: true }));
      input.dispatchEvent(new view.Event("focus", { bubbles: false }));
      var proto = input.tagName === "TEXTAREA" ? view.HTMLTextAreaElement.prototype : view.HTMLInputElement.prototype;
      var desc = Object.getOwnPropertyDescriptor(proto, "value");
      if (desc && desc.set) desc.set.call(input, val);
      else input.value = val;
      if (input._valueTracker) {
        try { input._valueTracker.setValue(""); } catch (e) {}
      }
      dispatchInputEvent(input, view, val);
      input.dispatchEvent(new view.Event("change", { bubbles: true }));
      if (!deferBlur) {
        input.dispatchEvent(new view.Event("blur", { bubbles: false }));
      }
    } catch (e) {}
  }

  function setNativeChecked(input, checkedVal) {
    try {
      var view = getDocView(input);
      var desc = Object.getOwnPropertyDescriptor(view.HTMLInputElement.prototype, "checked");
      if (desc && desc.set) desc.set.call(input, !!checkedVal);
      else input.checked = !!checkedVal;
      if (input._valueTracker) {
        try { input._valueTracker.setValue(checkedVal ? "false" : "true"); } catch (e) {}
      }
      input.dispatchEvent(new view.Event("input", { bubbles: true }));
      input.dispatchEvent(new view.Event("change", { bubbles: true }));
    } catch (e) {}
  }

  function setNativeSelectValue(selectEl, targetIdx) {
    try {
      var view = getDocView(selectEl);
      var opt = selectEl.options[targetIdx];
      var nextVal = opt ? opt.value : "";
      var desc = Object.getOwnPropertyDescriptor(view.HTMLSelectElement.prototype, "value");
      if (desc && desc.set) desc.set.call(selectEl, nextVal);
      else selectEl.selectedIndex = targetIdx;
      if (selectEl._valueTracker) {
        try { selectEl._valueTracker.setValue(""); } catch (e) {}
      }
      selectEl.dispatchEvent(new view.Event("input", { bubbles: true }));
      selectEl.dispatchEvent(new view.Event("change", { bubbles: true }));
    } catch (e) {}
  }

  function setEditableText(el, val) {
    try {
      var view = getDocView(el);
      el.dispatchEvent(new view.Event("focus", { bubbles: true }));
      el.textContent = val;
      dispatchInputEvent(el, view, val);
      el.dispatchEvent(new view.Event("change", { bubbles: true }));
    } catch (e) {}
  }

  function fillScope(scope, formFill, shadowDepth, sharedPasswordRef) {
    if (!scope || (shadowDepth || 0) > 8) return;
    var overwrite = !!(formFill && formFill.overwrite);
    var blurredInputs = [];
    var pwdRef = sharedPasswordRef || { primary: "" };

    if (scope.querySelectorAll) {
      var inputs = scope.querySelectorAll("input, textarea, select");
      for (var i = 0; i < inputs.length; i++) {
        var inp = inputs[i];
        if (inp.disabled || !isVisible(inp)) continue;
        if (closestAcrossShadow(inp, "[data-myth-ui='1']")) continue;

        var tag = inp.tagName;
        if (tag === "SELECT") {
          if (inp.options && inp.options.length > 0 && (!inp.value || inp.selectedIndex === 0 || overwrite)) {
            for (var o = 0; o < inp.options.length; o++) {
              if (!inp.options[o].disabled && inp.options[o].value !== "") {
                setNativeSelectValue(inp, o);
                break;
              }
            }
          }
          continue;
        }

        var type = (inp.type || "text").toLowerCase();
        if (type === "hidden" || type === "submit" || type === "button" || type === "reset" || type === "image") continue;

        if (inp.readOnly) {
          if (isDatepickerInput(inp) && (!inp.value || overwrite)) {
            setNativeInputValue(inp, resolveValueForInput(inp, formFill, pwdRef), true);
            blurredInputs.push(inp);
          }
          continue;
        }

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
            setNativeChecked(inp, true);
          }
          continue;
        }

        if (!overwrite && inp.value && String(inp.value).trim() !== "") {
          if (type === "password" && !pwdRef.primary) pwdRef.primary = String(inp.value);
          continue;
        }
        setNativeInputValue(inp, resolveValueForInput(inp, formFill, pwdRef), true);
        blurredInputs.push(inp);
      }

      // Deferred blur after filling batch so cross-field validation & popovers stay stable
      for (var b = 0; b < blurredInputs.length; b++) {
        try {
          var bv = getDocView(blurredInputs[b]);
          blurredInputs[b].dispatchEvent(new bv.Event("blur", { bubbles: false }));
          blurredInputs[b].dispatchEvent(new bv.FocusEvent("focusout", { bubbles: true }));
        } catch (e) {}
      }

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

    // Traverse nested open/closed shadow roots inside scope
    try {
      var doc = scope.ownerDocument || (scope.nodeType === 9 ? scope : document);
      var walker = doc.createTreeWalker(scope, NodeFilter.SHOW_ELEMENT, null);
      var node = scope.nodeType === 1 ? scope : walker.nextNode();
      var cnt = 0;
      while (node && cnt < 8000) {
        cnt++;
        var sr = getShadowRoot(node);
        if (sr) fillScope(sr, formFill, (shadowDepth || 0) + 1, pwdRef);
        node = walker.nextNode();
      }
    } catch (e) {}
  }

  function autoFillNearestForm(triggerEl, formFill) {
    var doc = triggerEl.ownerDocument || document;
    var scope = closestAcrossShadow(triggerEl, "form,[role='dialog'],.modal,table,section,main") || doc.body;
    fillScope(scope, formFill, 0);
  }

  async function hoverDropdownTriggers(root, frameDepth, shadowDepth) {
    if (!root || (frameDepth || 0) > 3 || (shadowDepth || 0) > 6) return;
    try {
      if (root.querySelectorAll) {
        var triggers = root.querySelectorAll("[aria-haspopup='true'], nav li, .dropdown-toggle, [data-bs-toggle='dropdown']");
        var maxHover = Math.min(triggers.length, 25);
        for (var i = 0; i < maxHover; i++) {
          var t = triggers[i];
          if (!isVisible(t)) continue;
          t.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
          t.dispatchEvent(new MouseEvent("mouseover", { bubbles: true }));
          await sleep(45);
        }
      }
      var doc = root.ownerDocument || (root.nodeType === 9 ? root : document);
      var start = (root.nodeType === 9) ? (root.body || root.documentElement) : root;
      if (start) {
        var walker = doc.createTreeWalker(start, NodeFilter.SHOW_ELEMENT, null);
        var n = start.nodeType === 1 ? start : walker.nextNode();
        var cnt = 0;
        while (n && cnt < 6000) {
          cnt++;
          var sr = getShadowRoot(n);
          if (sr) await hoverDropdownTriggers(sr, frameDepth || 0, (shadowDepth || 0) + 1);
          if (n.tagName === "IFRAME" || n.tagName === "FRAME") {
            try {
              if (n.contentDocument) await hoverDropdownTriggers(n.contentDocument, (frameDepth || 0) + 1, 0);
            } catch (e) {}
          }
          n = walker.nextNode();
        }
      }
    } catch (e) {}
  }

  async function performLazyScrollSweep() {
    try {
      var h = Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0);
      if (h > window.innerHeight + 100) {
        var step = Math.max(300, Math.floor(window.innerHeight * 0.85));
        for (var y = step; y <= h && y <= step * 6; y += step) {
          window.scrollTo(0, y);
          await sleep(140);
        }
        window.scrollTo(0, 0);
        await sleep(120);
      }

      // Inner scrollable / virtualized list containers (overflow: auto/scroll)
      var candidates = document.querySelectorAll("div, section, ul, tbody, [data-virtualized]");
      var scrolledContainers = 0;
      for (var i = 0; i < candidates.length && scrolledContainers < 6; i++) {
        var c = candidates[i];
        if (!c || c.scrollHeight <= c.clientHeight + 80 || c.clientHeight < 120) continue;
        var cs = window.getComputedStyle(c);
        if (!cs || (cs.overflowY !== "auto" && cs.overflowY !== "scroll")) continue;
        scrolledContainers++;
        var cStep = Math.max(180, Math.floor(c.clientHeight * 0.85));
        for (var sy = cStep; sy <= c.scrollHeight && sy <= cStep * 5; sy += cStep) {
          c.scrollTop = sy;
          await sleep(110);
        }
        c.scrollTop = 0;
      }
    } catch (e) {}
  }

  function getGlobalChromeSignature(el, info) {
    try {
      if (!el) return "";
      if (closestAcrossShadow(el, "[role='dialog'], [aria-modal='true'], .modal, .drawer, main")) return "";
      var chromeEl = closestAcrossShadow(el, "header, footer, nav, [role='banner'], [role='contentinfo'], [role='navigation']");
      if (!chromeEl) return "";
      var scope = chromeEl.tagName === "FOOTER" || chromeEl.getAttribute("role") === "contentinfo"
        ? "footer"
        : (chromeEl.tagName === "HEADER" || chromeEl.getAttribute("role") === "banner" ? "header" : "nav");
      var hrefPath = "";
      var a = el.tagName === "A" ? el : closestAcrossShadow(el, "a[href]");
      if (a) {
        var raw = (a.getAttribute("href") || "").trim();
        if (raw) {
          try { hrefPath = new URL(raw, getDocLocationHref(el)).pathname; } catch (e) { hrefPath = raw; }
        }
      }
      return [scope, info.kind || "", (info.label || "").toLowerCase(), hrefPath].join("|");
    } catch (e) {
      return "";
    }
  }

  function detectCaptchaOrChallengeInRoot(root, depth) {
    if (!root || (depth || 0) > 6) return "";
    try {
      if (root.querySelector) {
        var hit = root.querySelector(
          "iframe[src*='captcha'], iframe[src*='recaptcha'], iframe[src*='turnstile'], " +
          "iframe[src*='hcaptcha'], iframe[src*='arkoselabs'], iframe[src*='funcaptcha'], iframe[src*='datadome'], " +
          ".g-recaptcha, .h-captcha, .cf-turnstile, #cf-challenge-running, #challenge-form, " +
          "#FunCaptcha, .geetest_holder, #aws-waf-captcha, #px-captcha"
        );
        if (hit && isVisible(hit)) return "Widget CAPTCHA/WAF Challenge";
      }
      var doc = root.ownerDocument || (root.nodeType === 9 ? root : document);
      var start = (root.nodeType === 9) ? (root.body || root.documentElement) : root;
      if (start) {
        var walker = doc.createTreeWalker(start, NodeFilter.SHOW_ELEMENT, null);
        var n = start.nodeType === 1 ? start : walker.nextNode();
        var cnt = 0;
        while (n && cnt < 4000) {
          cnt++;
          var sr = getShadowRoot(n);
          if (sr) {
            var res = detectCaptchaOrChallengeInRoot(sr, (depth || 0) + 1);
            if (res) return res;
          }
          n = walker.nextNode();
        }
      }
    } catch (e) {}
    return "";
  }

  function detectCaptchaOrChallenge() {
    try {
      var u = (location.href || "").toLowerCase();
      if (u.indexOf("/verify/captcha") >= 0 || u.indexOf("/cdn-cgi/challenge") >= 0) {
        return "URL challenge/captcha";
      }
      return detectCaptchaOrChallengeInRoot(document, 0);
    } catch (e) {}
    return "";
  }

  async function dispatchRealisticClick(el, view) {
    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : { left: 10, top: 10, width: 20, height: 12 };
    var cx = Math.round(rect.left + rect.width * (0.4 + Math.random() * 0.2));
    var cy = Math.round(rect.top + rect.height * (0.4 + Math.random() * 0.2));
    var opts = {
      bubbles: true,
      cancelable: true,
      view: view,
      clientX: cx,
      clientY: cy,
      screenX: cx,
      screenY: cy,
      button: 0,
      buttons: 1
    };
    try {
      if (view.PointerEvent) el.dispatchEvent(new view.PointerEvent("pointerdown", opts));
      el.dispatchEvent(new view.MouseEvent("mousedown", opts));
      await sleep(15 + Math.floor(Math.random() * 30));
      opts.buttons = 0;
      if (view.PointerEvent) el.dispatchEvent(new view.PointerEvent("pointerup", opts));
      el.dispatchEvent(new view.MouseEvent("mouseup", opts));
    } catch (e) {}
    el.click();
  }

  function pickUnvisitedSelectOption(selectEl, baseSig, clickedSet) {
    if (!selectEl || selectEl.tagName !== "SELECT" || !selectEl.options || selectEl.options.length <= 1) {
      return null;
    }
    var validCount = 0;
    for (var o = 0; o < selectEl.options.length && validCount < 4; o++) {
      var opt = selectEl.options[o];
      if (!opt || opt.disabled || opt.value === "") continue;
      validCount++;
      var optSig = baseSig + "|opt:" + o;
      if (!clickedSet.has(optSig)) {
        return {
          optIndex: o,
          optSignature: optSig,
          optLabel: sanitizeLabel(opt.text || opt.value, 28)
        };
      }
    }
    return null;
  }

  function pickUnvisitedComboboxPass(el, baseSig, clickedSet) {
    var role = (el.getAttribute && (el.getAttribute("role") || "")).toLowerCase();
    var isCb = role === "combobox" || ((el.getAttribute && (el.getAttribute("aria-haspopup") || "")).toLowerCase() === "listbox");
    if (!isCb) return null;
    for (var pass = 0; pass < 3; pass++) {
      var cbSig = baseSig + "|cbopt:" + pass;
      if (!clickedSet.has(cbSig)) {
        return { passIndex: pass, optSignature: cbSig };
      }
    }
    return null;
  }

  function getRepeatingRowTemplateKey(el, info, routeKey) {
    if (!el || !info) return "";
    try {
      var rowEl = closestAcrossShadow(el, "tbody > tr, [role='row'], ul > li, ol > li");
      if (!rowEl || !rowEl.parentNode || !rowEl.parentNode.children) return "";
      var parent = rowEl.parentNode;
      var pTag = (parent.tagName || "").toUpperCase();
      // Never cap navigation menus (nav > ul > li or role=menubar/tablist)
      if (closestAcrossShadow(rowEl, "nav, [role='navigation'], [role='tablist'], [role='menubar'], [role='menu']")) {
        return "";
      }
      var sibs = parent.children;
      var sameRowCount = 0;
      var rTag = rowEl.tagName;
      for (var i = 0; i < sibs.length && sameRowCount < 4; i++) {
        if (sibs[i].tagName === rTag) sameRowCount++;
      }
      if (sameRowCount < 3) return "";
      var containerKey = buildStructuralPath(parent) || pTag;
      return [
        routeKey || "",
        "ROW_TPL",
        containerKey,
        info.tag || "",
        info.kind || "",
        normalizeSigText(info.label || "")
      ].join("|");
    } catch (e) {
      return "";
    }
  }

  async function triggerElementAction(targetEl, targetInfo, targetMeta) {
    var view = getDocView(targetEl);
    var doc = targetEl.ownerDocument || document;

    if (targetInfo.kind === "select" && targetEl.tagName === "SELECT" && targetEl.options && targetEl.options.length > 1) {
      var nextIdx = (targetMeta && typeof targetMeta.selectOptIdx === "number")
        ? targetMeta.selectOptIdx
        : ((targetEl.selectedIndex + 1) % targetEl.options.length);
      if (targetEl.options[nextIdx] && (targetEl.options[nextIdx].disabled || targetEl.options[nextIdx].value === "")) {
        for (var o = 0; o < targetEl.options.length; o++) {
          if (o !== targetEl.selectedIndex && !targetEl.options[o].disabled && targetEl.options[o].value !== "") {
            nextIdx = o;
            break;
          }
        }
      }
      setNativeSelectValue(targetEl, nextIdx);
      return;
    }

    if (targetEl.tagName === "INPUT" && ((targetEl.type || "").toLowerCase() === "search" || targetEl.getAttribute("role") === "searchbox")) {
      try {
        targetEl.focus();
        var val = (currentConfig && currentConfig.formFill && currentConfig.formFill.search) ? currentConfig.formFill.search : "TEXT";
        if (!targetEl.value) {
          setNativeInputValue(targetEl, val, true);
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

    var role = (targetEl.getAttribute("role") || "").toLowerCase();
    var isCombobox = role === "combobox" || (targetEl.getAttribute("aria-haspopup") || "").toLowerCase() === "listbox";

    await dispatchRealisticClick(targetEl, view);

    // Custom Combobox / MUI / AntD / Radix portal listbox multi-option picking
    if (isCombobox) {
      await sleep(150);
      try {
        var listbox = doc.querySelector(
          "[role='listbox'], .ant-select-dropdown:not(.ant-select-dropdown-hidden), .MuiMenu-paper, .select2-results__options"
        );
        if (listbox && isVisible(listbox)) {
          var opts = listbox.querySelectorAll("[role='option']:not([aria-disabled='true']), .ant-select-item-option:not(.ant-select-item-option-disabled), .select2-results__option");
          var visibleOpts = [];
          for (var oi = 0; oi < opts.length; oi++) {
            if (isVisible(opts[oi])) visibleOpts.push(opts[oi]);
          }
          if (visibleOpts.length > 0) {
            var desiredIdx = (targetMeta && typeof targetMeta.comboboxPassIdx === "number")
              ? Math.min(targetMeta.comboboxPassIdx, visibleOpts.length - 1)
              : 0;
            await dispatchRealisticClick(visibleOpts[desiredIdx], view);
          }
        }
      } catch (e) {}
    }
  }

  var lastTelemetryAt = 0;
  var lastTelemetryPhase = "";
  var lastTelemetryDone = -1;
  var lastTelemetryTotal = -1;

  function reportTelemetry(phase, actionsDone, totalTargets, force) {
    try {
      var p = phase || "IDLE";
      var done = Math.max(0, actionsDone | 0);
      var total = Math.max(0, totalTargets | 0);
      var now = Date.now();
      if (!force && p === lastTelemetryPhase && (now - lastTelemetryAt) < 350) {
        return;
      }
      if (!force && p === lastTelemetryPhase && done === lastTelemetryDone && total === lastTelemetryTotal && (now - lastTelemetryAt) < 2500) {
        return;
      }
      lastTelemetryAt = now;
      lastTelemetryPhase = p;
      lastTelemetryDone = done;
      lastTelemetryTotal = total;
      apiCall(CONTROL + "/telemetry", "POST", {
        phase: p,
        pageUrl: location.href,
        actionsDone: done,
        totalTargets: total
      }).catch(function () {});
    } catch (e) {}
  }

  function isPaginationControl(el, info) {
    try {
      if (!el || !info) return false;
      var lbl = (info.label || "").trim();
      var aria = (el.getAttribute && (el.getAttribute("aria-label") || "")) || "";
      var rel = (el.getAttribute && (el.getAttribute("rel") || "")) || "";
      var cls = typeof el.className === "string" ? el.className : "";
      var txt = (lbl + " " + aria).trim();
      if (/^(next|prev|previous)$/i.test(rel)) return true;
      if (/\b(load\s*more|show\s*more|view\s*more|next\s*page|prev\s*page|previous\s*page)\b|xem\s*thêm|tải\s*thêm|hiển\s*thị\s*thêm|trang\s*(sau|tiếp|kế)/i.test(txt)) {
        return true;
      }
      var inPager = false;
      try {
        var pagerAnc = closestAcrossShadow(el, "[class*='paginat'], [class*='pager'], [aria-label*='paginat' i], [aria-label*='trang' i], nav");
        if (pagerAnc) {
          var pCls = typeof pagerAnc.className === "string" ? pagerAnc.className : "";
          var pAria = (pagerAnc.getAttribute && (pagerAnc.getAttribute("aria-label") || "")) || "";
          if (/paginat|pager|trang/i.test(pCls + " " + pAria + " " + cls)) {
            inPager = true;
          }
        }
      } catch (ignored) {}
      if (inPager && (/^\d{1,4}$/.test(lbl) || /^(next|prev|previous|sau|trước|[<>«»›‹]+)$/i.test(lbl))) {
        return true;
      }
    } catch (e) {}
    return false;
  }

  function dismissActiveModalWithEscape() {
    try {
      var target = document.activeElement || document.body || document.documentElement;
      var evInit = { key: "Escape", code: "Escape", keyCode: 27, which: 27, bubbles: true, cancelable: true };
      if (target && typeof target.dispatchEvent === "function") {
        target.dispatchEvent(new KeyboardEvent("keydown", evInit));
        target.dispatchEvent(new KeyboardEvent("keyup", evInit));
      }
      document.dispatchEvent(new KeyboardEvent("keydown", evInit));
      document.dispatchEvent(new KeyboardEvent("keyup", evInit));
      var openDialogs = document.querySelectorAll ? document.querySelectorAll("dialog[open]") : [];
      for (var d = 0; d < openDialogs.length; d++) {
        try {
          if (typeof openDialogs[d].close === "function") openDialogs[d].close();
        } catch (ignored) {}
      }
    } catch (e) {}
  }

  async function runCrawlPass() {
    if (runningLoop) return;
    runningLoop = true;
    installCrawlGuards();
    installDomMutationObserver();
    try {
      var state = await apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href));
      currentConfig = state;
      applyServiceWorkerBypassGuard(state);
      if (state.status !== "RUNNING" || !state.allowAutoRequests) {
        reportTelemetry(state.status === "PAUSED" ? "PAUSED" : "IDLE", 0, 0, true);
        updateBadge(state.status === "RUNNING" ? "CHỜ CHO PHÉP (Thủ công)" : state.status, "#475569");
        if (state.status === "STOPPED" || state.status === "PAUSED") disconnectDomMutationObserver();
        return;
      }

      if (!isAllowedUrl(location.href, state)) {
        if (state.seedUrl && isAllowedUrl(state.seedUrl, state) && state.seedUrl !== location.href) {
          reportTelemetry("NAVIGATING", 0, 0, true);
          updateBadge("Ngoài phạm vi -> chuyển tới " + state.seedUrl, "#b45309");
          location.replace(state.seedUrl);
          return;
        }
        var btInit = await apiCall(CONTROL + "/backtrack", "POST", {
          url: location.href,
          defer: "1",
          dest: location.href,
          reason: "Trang hiện tại nằm ngoài phạm vi crawl cho phép: " + location.href
        }).catch(function () { return null; });
        if (btInit && !btInit.done && btInit.targetUrl && isAllowedUrl(btInit.targetUrl, state)) {
          location.replace(btInit.targetUrl);
          return;
        }
        reportTelemetry("IDLE", 0, 0, true);
        updateBadge("Chặn crawl: URL ngoài phạm vi cho phép", "#b91c1c");
        return;
      }

      var captchaReason = detectCaptchaOrChallenge();
      if (captchaReason) {
        reportTelemetry("PAUSED", 0, 0, true);
        updateBadge("Phát hiện CAPTCHA/WAF - đã Tạm dừng", "#b91c1c");
        await apiCall(CONTROL + "/note", "POST", {
          actionPath: formatActionPath(location.href, "page load", "", state),
          kind: "page load",
          label: "",
          pageUrl: location.href,
          noteType: "REDIRECT_DEFERRED",
          severity: "WARN",
          method: "NAV",
          endpoint: location.href,
          message: "Tạm dừng auto-crawl do phát hiện " + captchaReason + " tại " + location.href
        }).catch(function () {});
        await apiCall(CONTROL + "/pause", "POST", {}).catch(function () {});
        disconnectDomMutationObserver();
        return;
      }

      updateBadge("Đang quét " + location.pathname, "#0f766e");
      reportTelemetry("LOADING", 0, 0, true);
      var pageStartAt = Date.now();
      var pausedApprovalMs = 0;
      var lastTotalTargets = 0;
      var pageLoadPath = formatActionPath(location.href, "page load", "", state);
      await apiCall(CONTROL + "/context", "POST", {
        actionPath: pageLoadPath,
        kind: "page load",
        uiAction: "navigate",
        label: "",
        pageUrl: location.href
      });
      await waitForNetworkSettle(550, 3000, 180);

      await hoverDropdownTriggers(document, 0, 0);
      if (state.deepCrawlMode) {
        reportTelemetry("SCROLLING", 0, 0, true);
        await performLazyScrollSweep();
      }
      if (state.autoFillForms) {
        reportTelemetry("FILLING_FORM", 0, 0, true);
        fillScope(document.body, state.formFill, 0);
      }

      var links = [];
      discoverLinks(document, links, 0, 0, state, location.href);
      discoverSpaFrameworkRoutes(links, state);
      await apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: links, skipped: [] });

      // Route-partitioned clickedSet cache: routeKey -> Set<signature>
      var clickedByRoute = new Map();
      var rowTemplateCounts = new Map();
      var maxRowSamples = 2;
      function getClickedSetForRoute(rk, seedArray) {
        var s = clickedByRoute.get(rk);
        if (!s) {
          s = new Set(Array.isArray(seedArray) ? seedArray : []);
          clickedByRoute.set(rk, s);
        } else if (Array.isArray(seedArray)) {
          for (var idx = 0; idx < seedArray.length; idx++) s.add(seedArray[idx]);
        }
        return s;
      }

      var initialRouteKey = getRouteKey(location.href, state);
      getClickedSetForRoute(initialRouteKey, state.clickedSignatures);
      var globalClickedSet = new Set(state.globalClickedSignatures || []);
      var actionsDone = 0;
      var spaTransitions = 0;
      var paginationClicksOnPage = 0;
      var modalEscapeAttempts = 0;
      var pendingSpaSimHashCheck = false;
      var maxActions = state.maxActionsPerPage || 300;
      var lazySweepDone = false;
      var passRouteUrl = location.href;

      while (actionsDone < maxActions) {
        if (spaRouteDirty && location.href !== passRouteUrl) {
          spaRouteDirty = false;
          if (!isAllowedUrl(location.href, currentConfig)) {
            var disallowedSpa = location.href;
            await postExternalRedirectNote(
              formatActionPath(passRouteUrl, "spa navigate", "", currentConfig),
              disallowedSpa,
              "Chặn SPA điều hướng ra ngoài phạm vi crawl: " + disallowedSpa,
              "spa navigate",
              ""
            );
            var btSpa = await apiCall(CONTROL + "/backtrack", "POST", { url: disallowedSpa, defer: "1", dest: disallowedSpa });
            var retSpa = (btSpa && btSpa.targetUrl && isAllowedUrl(btSpa.targetUrl, currentConfig)) ? btSpa.targetUrl : passRouteUrl;
            location.replace(retSpa);
            return;
          }
          passRouteUrl = location.href;
          pendingSpaSimHashCheck = true;
          var spaLoadPath = formatActionPath(passRouteUrl, "page load", "", currentConfig);
          await apiCall(CONTROL + "/context", "POST", {
            actionPath: spaLoadPath,
            kind: "page load",
            uiAction: "navigate",
            label: "",
            pageUrl: passRouteUrl
          }).catch(function () {});
        }

        var freshState = await apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href));
        currentConfig = freshState;
        if (freshState.status !== "RUNNING" || !freshState.allowAutoRequests) {
          reportTelemetry(freshState.status === "PAUSED" ? "PAUSED" : "IDLE", actionsDone, lastTotalTargets, true);
          updateBadge(!freshState.allowAutoRequests ? "ĐÃ KHÓA TỰ GỬI" : freshState.status, "#b45309");
          if (freshState.status === "STOPPED" || freshState.status === "PAUSED") disconnectDomMutationObserver();
          return;
        }

        // Guardrail INTERCEPT_WAIT Awareness: wait smoothly without burning page watchdog budget
        if ((freshState.pendingInterceptCount || 0) > 0) {
          var waitStart = Date.now();
          while ((freshState.pendingInterceptCount || 0) > 0) {
            var pend = freshState.pendingInterceptCount | 0;
            updateBadge("Đang chờ bạn duyệt trên Burp (" + pend + " mục)...", "#b45309");
            reportTelemetry("WAITING_APPROVAL", actionsDone, lastTotalTargets, false);
            await sleep(650);
            freshState = await apiCall(CONTROL + "/state?url=" + encodeURIComponent(location.href));
            currentConfig = freshState;
            if (freshState.status !== "RUNNING" || !freshState.allowAutoRequests) {
              pausedApprovalMs += (Date.now() - waitStart);
              reportTelemetry(freshState.status === "PAUSED" ? "PAUSED" : "IDLE", actionsDone, lastTotalTargets, true);
              updateBadge(!freshState.allowAutoRequests ? "ĐÃ KHÓA TỰ GỬI" : freshState.status, "#b45309");
              if (freshState.status === "STOPPED" || freshState.status === "PAUSED") disconnectDomMutationObserver();
              return;
            }
          }
          pausedApprovalMs += (Date.now() - waitStart);
        }

        // Page-duration watchdog (excluding WAITING_APPROVAL time)
        var maxPageDurationMs = (freshState.maxPageDurationMs && freshState.maxPageDurationMs > 0)
          ? freshState.maxPageDurationMs : 90000;
        var activeElapsedMs = (Date.now() - pageStartAt) - pausedApprovalMs;
        if (activeElapsedMs >= maxPageDurationMs) {
          updateBadge("Hết thời lượng trang (" + Math.round(activeElapsedMs / 1000) + "s) -> chuyển trang", "#0369a1");
          break;
        }

        if (Array.isArray(freshState.globalClickedSignatures)) {
          for (var gIdx = 0; gIdx < freshState.globalClickedSignatures.length; gIdx++) {
            globalClickedSet.add(freshState.globalClickedSignatures[gIdx]);
          }
        }

        var curRouteKey = getRouteKey(location.href, freshState);
        var clickedSet = getClickedSetForRoute(curRouteKey, freshState.clickedSignatures);

        var items = [];
        collectAllInteractive(document, items, new WeakSet(), 0, 0, "", 0, 0, location.href, false);
        lastTotalTargets = items.length;
        var maxPagination = (freshState.maxPaginationSamples && freshState.maxPaginationSamples > 0)
          ? freshState.maxPaginationSamples : 5;
        var genSimKey = getGeneralizedSimHashKey(location.href, freshState);
        if (pendingSpaSimHashCheck) {
          pendingSpaSimHashCheck = false;
          var curSimHash = computeDomSimHash(items);
          if (isNearDuplicateSpaState(genSimKey, curSimHash)) {
            // Near-duplicate SPA state (Hamming <= 3) -> skip remaining redundant exploration on this route view
            break;
          }
          recordSpaSimHash(genSimKey, curSimHash);
        } else if (!seenStateSimHashes.has(genSimKey)) {
          recordSpaSimHash(genSimKey, computeDomSimHash(items));
        }

        var skippedBatch = [];
        var skippedDonePromises = [];
        var targetItem = null;
        var targetInfo = null;
        var targetGlobalSig = "";
        var targetEffectiveSig = "";
        var remainingUnclickedAfterTarget = 0;

        for (var i = 0; i < items.length; i++) {
          var item = items[i];
          var el = item.el;
          var fp = item.framePrefix;
          var itemRouteKey = item.frameHref ? getRouteKey(item.frameHref, freshState) : curRouteKey;

          // Cheap pre-check before expensive extractDirectVisibleText / region climbing
          var cheapKey = buildCheapCandidateKey(el, fp, itemRouteKey);
          if (clickedSet.has("CHEAP:" + cheapKey)) continue;

          var info = extractElementInfo(el, fp, itemRouteKey);
          var selChoice = (info.kind === "select" && el.tagName === "SELECT")
            ? pickUnvisitedSelectOption(el, info.signature, clickedSet)
            : null;
          var cbChoice = (!selChoice && info.kind === "select")
            ? pickUnvisitedComboboxPass(el, info.signature, clickedSet)
            : null;

          if (el.tagName === "SELECT" && el.options && el.options.length > 1) {
            if (!selChoice) {
              clickedSet.add(info.signature);
              clickedSet.add("CHEAP:" + cheapKey);
              continue;
            }
          } else if (cbChoice === null && (el.getAttribute("role") || "").toLowerCase() === "combobox") {
            clickedSet.add(info.signature);
            clickedSet.add("CHEAP:" + cheapKey);
            continue;
          } else if (clickedSet.has(info.signature)) {
            clickedSet.add("CHEAP:" + cheapKey);
            continue;
          }

          var gSig = getGlobalChromeSignature(el, info);
          if (gSig && globalClickedSet.has(gSig)) {
            clickedSet.add(info.signature);
            clickedSet.add("CHEAP:" + cheapKey);
            continue;
          }

          // Cap identical actions across 100+ repeating table rows / list items to maxRowSamples (2)
          var rowTplKey = getRepeatingRowTemplateKey(el, info, itemRouteKey);
          if (rowTplKey) {
            var tplUsed = rowTemplateCounts.get(rowTplKey) || 0;
            if (tplUsed >= maxRowSamples) {
              clickedSet.add(info.signature);
              clickedSet.add("CHEAP:" + cheapKey);
              continue;
            }
          }

          if (freshState.dangerGuardEnabled) {
            var badKw = isDangerousElement(el, info, freshState.dangerKeywords);
            if (badKw) {
              clickedSet.add(info.signature);
              clickedSet.add("CHEAP:" + cheapKey);
              var skippedPath = formatActionPath(item.frameHref || location.href, info.kindLabel, info.label, freshState, info.regionSegments, item.framePrefix);
              skippedBatch.push({
                actionPath: skippedPath,
                reason: "Danger Guard: " + badKw,
                tag: info.tag
              });
              skippedDonePromises.push(
                apiCall(CONTROL + "/done_action", "POST", { pageUrl: item.frameHref || location.href, signature: info.signature }).catch(function () {})
              );
              continue;
            }
          }
          if (isPaginationControl(el, info) && paginationClicksOnPage >= maxPagination) {
            clickedSet.add(info.signature);
            clickedSet.add("CHEAP:" + cheapKey);
            continue;
          }

          if (!targetItem) {
            targetItem = item;
            targetInfo = info;
            targetGlobalSig = gSig;
            targetEffectiveSig = selChoice ? selChoice.optSignature : (cbChoice ? cbChoice.optSignature : info.signature);
            targetItem._cheapKey = (selChoice || cbChoice) ? "" : cheapKey;
            targetItem._isPagination = isPaginationControl(el, info);
            targetItem._rowTplKey = rowTplKey;
            targetItem._selectOptIdx = selChoice ? selChoice.optIndex : undefined;
            targetItem._selectOptLabel = selChoice ? selChoice.optLabel : "";
            targetItem._comboboxPassIdx = cbChoice ? cbChoice.passIndex : undefined;
          } else {
            remainingUnclickedAfterTarget++;
            if (remainingUnclickedAfterTarget >= 3) {
              break; // Enough to know page still has unclicked actions if navigation happens
            }
          }
        }

        if (skippedDonePromises.length > 0) {
          await Promise.all(skippedDonePromises);
        }
        if (skippedBatch.length > 0) {
          await apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: [], skipped: skippedBatch });
        }

        if (!targetItem) {
          if (modalEscapeAttempts < 3 && hasVisibleModalInDoc(document)) {
            modalEscapeAttempts++;
            dismissActiveModalWithEscape();
            await waitForNetworkSettle(250, 1200, 120);
            continue;
          }
          if (!lazySweepDone && freshState.deepCrawlMode) {
            lazySweepDone = true;
            reportTelemetry("SCROLLING", actionsDone, lastTotalTargets, true);
            await performLazyScrollSweep();
            await waitForNetworkSettle(350, 1800, 150);
            continue;
          }
          break;
        }

        if (targetItem._isPagination) {
          paginationClicksOnPage++;
        }

        var targetEl = targetItem.el;
        var sigToRecord = targetEffectiveSig || targetInfo.signature;
        clickedSet.add(sigToRecord);
        if (targetItem._cheapKey) clickedSet.add("CHEAP:" + targetItem._cheapKey);
        if (targetItem._rowTplKey) {
          rowTemplateCounts.set(targetItem._rowTplKey, (rowTemplateCounts.get(targetItem._rowTplKey) || 0) + 1);
        }

        var beforeUrl = location.href;
        var itemDocUrl = targetItem.frameHref || beforeUrl;
        var actionLabel = targetInfo.label;
        if (targetItem._selectOptLabel) {
          actionLabel = sanitizeLabel(actionLabel + " [" + targetItem._selectOptLabel + "]", 56);
        }
        var actionPath = formatActionPath(itemDocUrl, targetInfo.kindLabel, actionLabel, freshState, targetInfo.regionSegments, targetItem.framePrefix);

        var linkEl = (targetEl.tagName === "A" || (targetEl.getAttribute && targetEl.getAttribute("href")))
          ? targetEl : closestAcrossShadow(targetEl, "a[href]");
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
              uiAction: resolveUiAction(targetEl, targetInfo, "navigate"),
              label: actionLabel,
              pageUrl: itemDocUrl
            });
            await postExternalRedirectNote(actionPath, trimmedHref, "Bỏ qua liên kết giao thức ngoài: " + trimmedHref, targetInfo.kindLabel, actionLabel);
            await apiCall(CONTROL + "/done_action", "POST", { pageUrl: itemDocUrl, signature: targetInfo.signature });
            actionsDone++;
            continue;
          }
          try {
            var dest = new URL(trimmedHref, itemDocUrl).href;
            if (!isAllowedUrl(dest, freshState)) {
              await apiCall(CONTROL + "/context", "POST", {
                actionPath: actionPath,
                kind: targetInfo.kindLabel,
                uiAction: resolveUiAction(targetEl, targetInfo, "navigate"),
                label: actionLabel,
                pageUrl: itemDocUrl,
                stepLocator: buildStepLocator(targetEl, targetInfo, targetItem.framePrefix)
              });
              await postExternalRedirectNote(actionPath, dest, "Đã chặn link ngoài phạm vi crawl: " + dest, targetInfo.kindLabel, actionLabel);
              await apiCall(CONTROL + "/done_action", "POST", { pageUrl: itemDocUrl, signature: targetInfo.signature });
              actionsDone++;
              continue;
            }
            if (dest !== itemDocUrl) {
              await apiCall(CONTROL + "/discovered", "POST", { pageUrl: itemDocUrl, urls: [dest], skipped: [] });
              var hasJsClick = clickableNodes.has(targetEl) || targetEl.hasAttribute("onclick") || hasFrameworkDelegatedAction(targetEl);
              if (targetInfo.kind === "link" && (!hasJsClick || remainingUnclickedAfterTarget > 0) && targetItem.tier === 4) {
                await apiCall(CONTROL + "/done_action", "POST", { pageUrl: itemDocUrl, signature: sigToRecord });
                actionsDone++;
                continue;
              }
            }
          } catch (e) {}
        }

        if (targetEl.getAttribute("target") === "_blank") {
          targetEl.setAttribute("target", "_self");
        }

        if (freshState.autoFillForms) {
          reportTelemetry("FILLING_FORM", actionsDone, lastTotalTargets, false);
          autoFillNearestForm(targetEl, freshState.formFill);
        }

        updateBadge(actionPath, "#1d4ed8");
        reportTelemetry("CLICKING", actionsDone + 1, lastTotalTargets, false);
        await apiCall(CONTROL + "/context", "POST", {
          actionPath: actionPath,
          kind: targetInfo.kindLabel,
          uiAction: resolveUiAction(targetEl, targetInfo, ""),
          label: actionLabel,
          pageUrl: itemDocUrl,
          stepLocator: buildStepLocator(targetEl, targetInfo, targetItem.framePrefix)
        });

        if (targetGlobalSig) {
          globalClickedSet.add(targetGlobalSig);
        }
        await apiCall(CONTROL + "/done_action", "POST", {
          pageUrl: itemDocUrl,
          signature: sigToRecord,
          globalSignature: targetGlobalSig || ""
        });
        actionsDone++;

        try {
          targetEl.scrollIntoView({ block: "center", inline: "center" });
        } catch (e) {}

        try {
          await triggerElementAction(targetEl, targetInfo, {
            selectOptIdx: targetItem._selectOptIdx,
            comboboxPassIdx: targetItem._comboboxPassIdx
          });
        } catch (e) {}

        await waitForNetworkSettle(freshState.actionWaitMs || 550, 3200, 180);

        var postCaptcha = detectCaptchaOrChallenge();
        if (postCaptcha) {
          updateBadge("Phát hiện CAPTCHA/WAF - đã Tạm dừng", "#b91c1c");
          await apiCall(CONTROL + "/note", "POST", {
            actionPath: actionPath,
            kind: targetInfo.kindLabel,
            label: actionLabel,
            pageUrl: location.href,
            noteType: "REDIRECT_DEFERRED",
            severity: "WARN",
            method: "NAV",
            endpoint: location.href,
            message: "Tạm dừng auto-crawl do phát hiện " + postCaptcha + " sau thao tác"
          }).catch(function () {});
          await apiCall(CONTROL + "/pause", "POST", {}).catch(function () {});
          disconnectDomMutationObserver();
          return;
        }

        var newLinks = [];
        discoverLinks(document, newLinks, 0, 0, freshState, location.href);
        if (newLinks.length > 0) {
          await apiCall(CONTROL + "/discovered", "POST", { pageUrl: location.href, urls: newLinks, skipped: [] });
        }

        if (location.href !== beforeUrl) {
          var jumpedUrl = location.href;
          if (!isAllowedUrl(jumpedUrl, freshState)) {
            await postExternalRedirectNote(actionPath, jumpedUrl, "Chặn nhảy ra ngoài phạm vi crawl: " + jumpedUrl, targetInfo.kindLabel, actionLabel);
            var btExt = await apiCall(CONTROL + "/backtrack", "POST", { url: jumpedUrl, defer: "1", dest: jumpedUrl });
            var retExt = (btExt && btExt.targetUrl && isAllowedUrl(btExt.targetUrl, freshState)) ? btExt.targetUrl : beforeUrl;
            location.replace(retExt);
            return;
          }
          // In-scope SPA transition (pushState / replaceState / hashchange):
          // Record route transition, re-queue beforeUrl if it still had unclicked controls, and continue exploring
          spaTransitions++;
          spaRouteDirty = false;
          pendingSpaSimHashCheck = true;
          passRouteUrl = jumpedUrl;
          await apiCall(CONTROL + "/discovered", "POST", {
            pageUrl: beforeUrl,
            incompletePageUrl: remainingUnclickedAfterTarget > 0 ? beforeUrl : "",
            urls: [jumpedUrl],
            skipped: []
          });
          var newSpaLoadPath = formatActionPath(jumpedUrl, "page load", "", freshState);
          await apiCall(CONTROL + "/context", "POST", {
            actionPath: newSpaLoadPath,
            kind: "page load",
            uiAction: "navigate",
            label: "",
            pageUrl: jumpedUrl
          }).catch(function () {});
          if (freshState.autoFillForms) {
            fillScope(document.body, freshState.formFill, 0);
          }
          if (spaTransitions >= 15) {
            break;
          }
        }
      }

      var nextResp = await apiCall(CONTROL + "/next_page?url=" + encodeURIComponent(location.href));
      if (nextResp && !nextResp.done && nextResp.nextUrl) {
        reportTelemetry("NAVIGATING", actionsDone, lastTotalTargets, true);
        updateBadge("Chuyển trang -> " + nextResp.nextUrl, "#0f766e");
        location.href = nextResp.nextUrl;
      } else {
        reportTelemetry("IDLE", actionsDone, lastTotalTargets, true);
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
    if (closestAcrossShadow(node, "[data-myth-ui='1']")) return null;
    var match = closestAcrossShadow(
      node,
      "button, a[href], a, select, input[type='submit'], input[type='button'], input[type='checkbox'], input[type='radio'], [role='button'], [role='tab'], [role='menuitem'], [role='link'], [role='combobox']"
    );
    if (match) return match;
    var cur = node;
    for (var d = 0; cur && d < 12; d++) {
      if (clickableNodes.has(cur) && !isCompositeInteractiveContainer(cur)) return cur;
      if (cur.tagName === "FORM" && !isCompositeInteractiveContainer(cur)) return cur;
      cur = getParentAcrossShadow(cur);
    }
    return node;
  }

  function resolveUiAction(el, info, evType) {
    try {
      if (evType === "navigate") return "navigate";
      var kind = info && info.kind ? String(info.kind).toLowerCase() : "";
      var lbl = info && info.label ? String(info.label) : "";
      var attrHint = "";
      if (el && el.getAttribute) {
        attrHint = [
          el.getAttribute("data-action") || "",
          el.getAttribute("data-method") || "",
          el.getAttribute("aria-label") || "",
          el.getAttribute("title") || "",
          el.getAttribute("name") || "",
          el.getAttribute("id") || "",
          typeof el.className === "string" ? el.className : ""
        ].join(" ");
      }
      var combined = (lbl + " " + attrHint).toLowerCase();
      if (/\b(delete|remove|destroy|purge|drop|revoke|clear|erase|del)\b|xóa|xoá|huỷ|hủy/.test(combined)
        || (el && el.dataset && String(el.dataset.method || "").toUpperCase() === "DELETE")) {
        return "delete";
      }
      if (evType === "submit" || kind === "form"
        || (el && ((el.tagName === "INPUT" && el.type === "submit")
          || (el.tagName === "BUTTON" && (el.type || "").toLowerCase() === "submit")))) {
        return "submit";
      }
      if (evType === "change" || kind === "select" || kind === "checkbox" || kind === "radio"
        || (el && (el.tagName === "SELECT"
          || (el.tagName === "INPUT" && /^(checkbox|radio|date|time|datetime-local|file|range|color)$/i.test(el.type || ""))))) {
        return "change";
      }
      if (kind === "link" || (el && el.tagName === "A")) {
        var href = el && el.getAttribute ? (el.getAttribute("href") || "").trim() : "";
        if (href && href !== "#" && !href.toLowerCase().startsWith("javascript:")) {
          return "navigate";
        }
      }
    } catch (e) {}
    return "click";
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
      var docUrl = getDocLocationHref(targetEl);
      var rk = getRouteKey(docUrl, currentConfig);
      var info = extractElementInfo(targetEl, framePrefix || "", rk);
      var label = info.label;
      var kind = evType === "submit" ? "form" : (evType === "change" && info.tag === "select" ? "select" : info.kind);
      var kindLbl = kindDisplayLabel(kind);
      var uiAction = resolveUiAction(targetEl, info, evType);
      var ap = formatActionPath(docUrl, kindLbl, label, currentConfig, info.regionSegments, framePrefix);
      updateBadge("Thủ công: " + ap, "#0f766e");
      var bodyStr = JSON.stringify({
        actionPath: ap,
        kind: kindLbl,
        uiAction: uiAction,
        label: label,
        pageUrl: docUrl,
        stepLocator: buildStepLocator(targetEl, info, framePrefix)
      });

      var hasToken = typeof window.__MYTH_TOKEN__ === "string" && !!window.__MYTH_TOKEN__;
      if (!hasToken && navigator.sendBeacon) {
        try {
          var blob = new Blob([bodyStr], { type: "application/json" });
          navigator.sendBeacon(CONTROL + "/context", blob);
          return;
        } catch (ignored) {}
      }
      controlFetch(CONTROL + "/context", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: bodyStr,
        keepalive: true
      }).then(function (r) {
        return r && r.ok ? r.json() : null;
      }).then(function (d) {
        if (d && typeof d.contextId === "number" && d.contextId > 0) {
          activeContextId = d.contextId;
        }
      }).catch(function () {});
    } catch (e) {}
  }

  function installHumanContextTracker(doc, framePrefix) {
    try {
      if (!doc || trackedDocs.has(doc)) return;
      trackedDocs.add(doc);
      doc.addEventListener("pointerdown", function (ev) {
        if (ev && ev.isTrusted) {
          var target = (ev.composedPath && ev.composedPath()[0]) || ev.target;
          postManualContext(target, "click", framePrefix);
        }
      }, true);
      doc.addEventListener("submit", function (ev) {
        if (ev && ev.isTrusted) postManualContext(ev.target, "submit", framePrefix);
      }, true);
      doc.addEventListener("change", function (ev) {
        if (ev && ev.isTrusted && ev.target && (ev.target.tagName === "SELECT" || ev.target.type === "checkbox" || ev.target.type === "radio")) {
          postManualContext(ev.target, "change", framePrefix);
        }
      }, true);
      var frames = doc.querySelectorAll ? doc.querySelectorAll("iframe, frame") : [];
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

  // ---- Visual Action Trail Replay & UI Spotlight Locator (Burp 2026 Dark/Orange Shadow HUD) ----

  var replayState = {
    host: null,
    shadow: null,
    refs: null,
    plan: null,
    lastRenderedKey: "",
    autoTimer: null,
    trackTimer: null,
    lastScrolledStepKey: "",
    localSyncLockUntil: 0,
    collapsed: false,
    scrollBound: false
  };

  function destroyReplayHud() {
    if (replayState.autoTimer) {
      clearInterval(replayState.autoTimer);
      replayState.autoTimer = null;
    }
    if (replayState.trackTimer) {
      clearInterval(replayState.trackTimer);
      replayState.trackTimer = null;
    }
    if (replayState.scrollBound) {
      replayState.scrollBound = false;
      try {
        window.removeEventListener("scroll", repositionReplaySpotlight, true);
        window.removeEventListener("resize", repositionReplaySpotlight);
      } catch (e) {}
    }
    if (replayState.host && replayState.host.parentNode) {
      try { replayState.host.parentNode.removeChild(replayState.host); } catch (e) {}
    }
    replayState.host = null;
    replayState.shadow = null;
    replayState.refs = null;
    replayState.plan = null;
    replayState.lastRenderedKey = "";
    replayState.lastScrolledStepKey = "";
  }

  function findIframeByPath(rootDoc, framePath) {
    if (!rootDoc || !framePath) return null;
    var frames = rootDoc.querySelectorAll ? rootDoc.querySelectorAll("iframe, frame") : [];
    for (var i = 0; i < frames.length; i++) {
      var fr = frames[i];
      var nm = fr.getAttribute("name") || fr.id || "";
      if (nm === framePath || ("iframe#" + (i + 1)) === framePath || ("frame" + i) === framePath) {
        return fr;
      }
      try {
        if (fr.contentDocument) {
          var nested = findIframeByPath(fr.contentDocument, framePath);
          if (nested) return nested;
        }
      } catch (e) {}
    }
    return null;
  }

  function fallbackFindElementInRoot(root, step, depth) {
    if (!root || (depth || 0) > 8) return null;
    var wantedTag = (step.tag || "").toLowerCase();
    var wantedRole = (step.role || "").toLowerCase();
    var wantedLabel = sanitizeLabel(step.label || "", 52).toLowerCase();
    if (!wantedLabel && !wantedTag) return null;

    var doc = root.ownerDocument || (root.nodeType === 9 ? root : document);
    var start = root.nodeType === 9 ? (root.body || root.documentElement) : root;
    if (!start) return null;

    var walker = doc.createTreeWalker(start, NodeFilter.SHOW_ELEMENT, null);
    var n = start.nodeType === 1 ? start : walker.nextNode();
    var cnt = 0;
    var best = null;
    while (n && cnt < 8000) {
      cnt++;
      if (isVisible(n) && !closestAcrossShadow(n, "[data-myth-ui='1']")) {
        var tg = (n.tagName || "").toLowerCase();
        var rl = (n.getAttribute("role") || "").toLowerCase();
        var tagOk = !wantedTag || tg === wantedTag || (wantedTag === "button" && rl === "button");
        var roleOk = !wantedRole || rl === wantedRole;
        if (tagOk && roleOk) {
          var lbl = extractDirectVisibleText(n)
            || sanitizeLabel(n.getAttribute("aria-label"), 52)
            || sanitizeLabel(n.getAttribute("title"), 52)
            || sanitizeLabel(n.getAttribute("placeholder"), 52)
            || sanitizeLabel(n.getAttribute("name"), 44)
            || sanitizeLabel(n.value, 52);
          var lowLbl = (lbl || "").toLowerCase();
          if (wantedLabel && lowLbl === wantedLabel) {
            return n;
          }
          if (!best && wantedLabel && lowLbl && lowLbl.indexOf(wantedLabel) >= 0) {
            best = n;
          }
        }
      }
      var sr = getShadowRoot(n);
      if (sr) {
        var hit = fallbackFindElementInRoot(sr, step, (depth || 0) + 1);
        if (hit) return hit;
      }
      n = walker.nextNode();
    }
    return best;
  }

  function findShadowHostRecursive(root, selector, depth) {
    if (!root || !selector || (depth || 0) > 8) return null;
    try {
      if (root.querySelector) {
        var direct = root.querySelector(selector);
        if (direct) return direct;
      }
    } catch (e) {}
    var doc = root.ownerDocument || (root.nodeType === 9 ? root : document);
    var start = root.nodeType === 9 ? (root.body || root.documentElement) : root;
    if (!start) return null;
    var walker = doc.createTreeWalker(start, NodeFilter.SHOW_ELEMENT, null);
    var n = start.nodeType === 1 ? start : walker.nextNode();
    var cnt = 0;
    while (n && cnt < 5000) {
      cnt++;
      var sr = getShadowRoot(n);
      if (sr) {
        var found = findShadowHostRecursive(sr, selector, (depth || 0) + 1);
        if (found) return found;
      }
      n = walker.nextNode();
    }
    return null;
  }

  function locateStepElement(step) {
    if (!step) return { el: null, root: document, offX: 0, offY: 0, isPageLoad: false };
    var targetDoc = document;
    var offX = 0;
    var offY = 0;

    if (step.framePath) {
      var fr = findIframeByPath(document, step.framePath);
      if (fr) {
        try {
          if (fr.contentDocument) {
            var fRect = fr.getBoundingClientRect();
            offX = fRect.left;
            offY = fRect.top;
            targetDoc = fr.contentDocument;
          }
        } catch (e) {}
      }
    }

    var isPageLoad = (step.kind || "").toLowerCase() === "page load" && !step.cssSelector;
    if (isPageLoad) {
      return { el: targetDoc.body || targetDoc.documentElement, root: targetDoc, offX: offX, offY: offY, isPageLoad: true };
    }

    var curRoot = targetDoc;
    var shadows = Array.isArray(step.shadowSelectors) ? step.shadowSelectors : [];
    for (var i = 0; i < shadows.length; i++) {
      var hostSel = shadows[i];
      if (!hostSel || !curRoot) {
        curRoot = null;
        break;
      }
      var hostEl = null;
      try { hostEl = curRoot.querySelector ? curRoot.querySelector(hostSel) : null; } catch (e) {}
      if (!hostEl) hostEl = findShadowHostRecursive(curRoot, hostSel, 0);
      curRoot = hostEl ? getShadowRoot(hostEl) : null;
    }

    var found = null;
    if (curRoot && step.cssSelector) {
      try {
        var cand = curRoot.querySelector(step.cssSelector);
        if (cand && isVisible(cand)) found = cand;
      } catch (e) {}
    }
    if (!found && step.cssSelector && shadows.length === 0) {
      try {
        var topCand = targetDoc.querySelector(step.cssSelector);
        if (topCand && isVisible(topCand)) found = topCand;
      } catch (e) {}
    }
    if (!found) {
      found = fallbackFindElementInRoot(curRoot || targetDoc, step, 0);
    }
    return {
      el: found,
      root: curRoot || targetDoc,
      offX: offX,
      offY: offY,
      isPageLoad: false
    };
  }

  function ensureReplayHudMounted() {
    if (replayState.host && replayState.shadow && replayState.refs && document.documentElement.contains(replayState.host)) {
      return replayState.refs;
    }
    destroyReplayHud();
    var host = document.createElement("div");
    host.id = "__myth_replay_host";
    host.setAttribute("data-myth-ui", "1");
    host.style.cssText = "all:initial;position:fixed;inset:0;pointer-events:none;z-index:2147483646;";
    (document.documentElement || document.body).appendChild(host);

    var shadow = null;
    try {
      if (typeof origNativeAttachShadow === "function") {
        shadow = origNativeAttachShadow.call(host, { mode: "closed" });
      } else if (host.attachShadow) {
        shadow = host.attachShadow({ mode: "open" });
      }
    } catch (e) {}
    if (!shadow) {
      shadow = host;
    }

    var style = document.createElement("style");
    style.textContent = [
      ":host{all:initial;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;}",
      "@keyframes mythPulse{0%{box-shadow:0 0 0 2px rgba(255,102,51,.95),0 0 14px rgba(255,102,51,.55);}50%{box-shadow:0 0 0 5px rgba(255,102,51,.65),0 0 24px rgba(255,102,51,.9);}100%{box-shadow:0 0 0 2px rgba(255,102,51,.95),0 0 14px rgba(255,102,51,.55);}}",
      "@keyframes mythFinalPulse{0%{box-shadow:0 0 0 3px rgba(16,185,129,.95),0 0 18px rgba(255,102,51,.7);}50%{box-shadow:0 0 0 6px rgba(16,185,129,.7),0 0 28px rgba(255,102,51,.95);}100%{box-shadow:0 0 0 3px rgba(16,185,129,.95),0 0 18px rgba(255,102,51,.7);}}",
      ".overlay{position:fixed;inset:0;pointer-events:none;z-index:2147483646;}",
      ".spot{position:fixed;border:2.5px solid #FF6633;border-radius:6px;background:rgba(255,102,51,0.10);pointer-events:none;box-sizing:border-box;animation:mythPulse 1.35s infinite;display:none;}",
      ".spot.final{border-color:#10B981;background:rgba(16,185,129,0.14);animation:mythFinalPulse 1.15s infinite;}",
      ".spot-badge{position:fixed;pointer-events:none;background:#2B2D30;color:#DFE1E5;border:1px solid #FF6633;border-radius:6px;padding:4px 9px;font:600 11.5px/1.35 system-ui,sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.45);max-width:340px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;display:none;}",
      ".spot-badge.final{border-color:#10B981;color:#FFFFFF;background:#065F46;}",
      ".field-box{position:fixed;border:1.5px dashed #38BDF8;border-radius:4px;background:rgba(56,189,248,0.09);pointer-events:none;box-sizing:border-box;}",
      ".hud{position:fixed;top:14px;right:14px;width:332px;max-width:calc(100vw - 24px);background:#2B2D30;color:#DFE1E5;border:1px solid #43454A;border-radius:8px;box-shadow:0 10px 32px rgba(0,0,0,.55);pointer-events:auto;font:400 12px/1.4 system-ui,sans-serif;overflow:hidden;z-index:2147483647;}",
      ".hud-hdr{display:flex;align-items:center;justify-content:space-between;padding:8px 10px;background:#313335;border-bottom:1px solid #43454A;cursor:move;user-select:none;}",
      ".hud-title{font:700 12.5px/1.2 system-ui,sans-serif;color:#FFFFFF;display:flex;align-items:center;gap:6px;}",
      ".dot{width:8px;height:8px;border-radius:50%;background:#FF6633;display:inline-block;}",
      ".hdr-btns{display:flex;gap:4px;}",
      ".icon-btn{background:transparent;border:1px solid #43454A;color:#DFE1E5;border-radius:4px;width:22px;height:22px;cursor:pointer;font:600 11px/1 system-ui;display:inline-flex;align-items:center;justify-content:center;}",
      ".icon-btn:hover{background:#3C3F44;border-color:#FF6633;}",
      ".hud-body{padding:9px 10px;display:flex;flex-direction:column;gap:8px;}",
      ".ep-row{background:#313335;border:1px solid #43454A;border-radius:5px;padding:6px 8px;}",
      ".ep{font:600 11.5px/1.3 ui-monospace,Consolas,monospace;color:#FF8B60;word-break:break-all;}",
      ".meta{font-size:11px;color:#8C9197;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}",
      ".status-box{padding:6px 8px;border-radius:5px;font-size:11.5px;border:1px solid #43454A;background:#313335;}",
      ".status-box.ok{border-color:#2F65CA;background:rgba(47,101,202,0.18);color:#DFE1E5;}",
      ".status-box.final{border-color:#10B981;background:rgba(16,185,129,0.18);color:#ECFDF5;font-weight:600;}",
      ".status-box.warn{border-color:#F59E0B;background:rgba(245,158,11,0.16);color:#FDE68A;}",
      ".fields{display:flex;flex-wrap:wrap;gap:4px;margin-top:4px;}",
      ".field-chip{background:#232528;border:1px solid #43454A;border-radius:4px;padding:2px 6px;font:500 10.5px/1.25 ui-monospace,monospace;color:#7DD3FC;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}",
      ".steps{max-height:175px;overflow-y:auto;display:flex;flex-direction:column;gap:3px;padding-right:2px;}",
      ".step-item{display:flex;align-items:center;gap:6px;padding:5px 7px;border-radius:5px;background:#313335;border:1px solid transparent;cursor:pointer;text-align:left;color:#DFE1E5;font:400 11.5px/1.3 system-ui;}",
      ".step-item:hover{background:#393C40;border-color:#43454A;}",
      ".step-item.active{background:#2F65CA;color:#FFFFFF;border-color:#5A8DEE;font-weight:600;}",
      ".step-num{min-width:18px;height:18px;border-radius:9px;background:#43454A;color:#FFFFFF;font:700 10px/18px system-ui;text-align:center;}",
      ".step-item.active .step-num{background:#FF6633;}",
      ".step-lbl{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}",
      ".ctrl{display:flex;flex-wrap:wrap;gap:5px;padding-top:2px;border-top:1px solid #43454A;}",
      ".btn{flex:1 1 auto;padding:5px 8px;border-radius:5px;border:1px solid #43454A;background:#313335;color:#DFE1E5;font:600 11.5px/1.2 system-ui;cursor:pointer;white-space:nowrap;}",
      ".btn:hover:not(:disabled){background:#3C3F44;border-color:#6B7280;}",
      ".btn:disabled{opacity:0.42;cursor:not-allowed;}",
      ".btn-primary{background:#FF6633;border-color:#FF6633;color:#FFFFFF;}",
      ".btn-primary:hover:not(:disabled){background:#E55300;border-color:#E55300;}",
      ".btn-danger{border-color:#7F1D1D;color:#FCA5A5;}",
      ".btn-danger:hover{background:#7F1D1D;color:#FFFFFF;}"
    ].join("\n");
    shadow.appendChild(style);

    var overlay = document.createElement("div");
    overlay.className = "overlay";
    var spot = document.createElement("div");
    spot.className = "spot";
    var spotBadge = document.createElement("div");
    spotBadge.className = "spot-badge";
    var fieldsLayer = document.createElement("div");
    overlay.appendChild(fieldsLayer);
    overlay.appendChild(spot);
    overlay.appendChild(spotBadge);

    var hud = document.createElement("div");
    hud.className = "hud";
    var hdr = document.createElement("div");
    hdr.className = "hud-hdr";
    var titleEl = document.createElement("div");
    titleEl.className = "hud-title";
    var dot = document.createElement("span");
    dot.className = "dot";
    var titleTxt = document.createElement("span");
    titleTxt.textContent = "Tìm vị trí trên Web";
    titleEl.appendChild(dot);
    titleEl.appendChild(titleTxt);

    var hdrBtns = document.createElement("div");
    hdrBtns.className = "hdr-btns";
    var btnCollapse = document.createElement("button");
    btnCollapse.className = "icon-btn";
    btnCollapse.title = "Thu gọn / Mở rộng";
    btnCollapse.textContent = "▾";
    var btnTopClose = document.createElement("button");
    btnTopClose.className = "icon-btn";
    btnTopClose.title = "Đóng Spotlight";
    btnTopClose.textContent = "✕";
    hdrBtns.appendChild(btnCollapse);
    hdrBtns.appendChild(btnTopClose);
    hdr.appendChild(titleEl);
    hdr.appendChild(hdrBtns);

    var body = document.createElement("div");
    body.className = "hud-body";

    var epRow = document.createElement("div");
    epRow.className = "ep-row";
    var epText = document.createElement("div");
    epText.className = "ep";
    var metaText = document.createElement("div");
    metaText.className = "meta";
    epRow.appendChild(epText);
    epRow.appendChild(metaText);

    var statusBox = document.createElement("div");
    statusBox.className = "status-box";
    var statusText = document.createElement("div");
    var fieldsBox = document.createElement("div");
    fieldsBox.className = "fields";
    statusBox.appendChild(statusText);
    statusBox.appendChild(fieldsBox);

    var stepsList = document.createElement("div");
    stepsList.className = "steps";

    var ctrl = document.createElement("div");
    ctrl.className = "ctrl";
    var btnAuto = document.createElement("button");
    btnAuto.className = "btn btn-primary";
    var btnPrev = document.createElement("button");
    btnPrev.className = "btn";
    btnPrev.textContent = "⏮ Lùi";
    var btnNext = document.createElement("button");
    btnNext.className = "btn";
    btnNext.textContent = "⏭ Tiếp";
    var btnScroll = document.createElement("button");
    btnScroll.className = "btn";
    btnScroll.textContent = "🎯 Cuộn tới";
    var btnStop = document.createElement("button");
    btnStop.className = "btn btn-danger";
    btnStop.textContent = "⏹ Đóng";
    ctrl.appendChild(btnAuto);
    ctrl.appendChild(btnPrev);
    ctrl.appendChild(btnNext);
    ctrl.appendChild(btnScroll);
    ctrl.appendChild(btnStop);

    body.appendChild(epRow);
    body.appendChild(statusBox);
    body.appendChild(stepsList);
    body.appendChild(ctrl);
    hud.appendChild(hdr);
    hud.appendChild(body);
    shadow.appendChild(overlay);
    shadow.appendChild(hud);

    // Dragging support on HUD header
    var dragState = null;
    hdr.addEventListener("pointerdown", function (ev) {
      if (ev.target && ev.target.tagName === "BUTTON") return;
      var r = hud.getBoundingClientRect();
      dragState = { dx: ev.clientX - r.left, dy: ev.clientY - r.top };
      try { hdr.setPointerCapture(ev.pointerId); } catch (e) {}
    });
    hdr.addEventListener("pointermove", function (ev) {
      if (!dragState) return;
      var nx = Math.max(8, Math.min(window.innerWidth - 120, ev.clientX - dragState.dx));
      var ny = Math.max(8, Math.min(window.innerHeight - 60, ev.clientY - dragState.dy));
      hud.style.left = nx + "px";
      hud.style.top = ny + "px";
      hud.style.right = "auto";
    });
    hdr.addEventListener("pointerup", function () { dragState = null; });

    btnCollapse.addEventListener("click", function () {
      replayState.collapsed = !replayState.collapsed;
      stepsList.style.display = replayState.collapsed ? "none" : "flex";
      epRow.style.display = replayState.collapsed ? "none" : "block";
      btnCollapse.textContent = replayState.collapsed ? "▸" : "▾";
    });
    btnTopClose.addEventListener("click", function () {
      syncReplayPlan("stop", 0, false);
    });
    btnStop.addEventListener("click", function () {
      syncReplayPlan("stop", 0, false);
    });
    btnPrev.addEventListener("click", function () {
      if (!replayState.plan) return;
      var idx = Math.max(0, (replayState.plan.currentIndex || 0) - 1);
      syncReplayPlan("step", idx, false);
    });
    btnNext.addEventListener("click", function () {
      if (!replayState.plan) return;
      var steps = replayState.plan.steps || [];
      var idx = Math.min(Math.max(0, steps.length - 1), (replayState.plan.currentIndex || 0) + 1);
      syncReplayPlan("step", idx, false);
    });
    btnAuto.addEventListener("click", function () {
      if (!replayState.plan) return;
      var nextAuto = !replayState.plan.autoPlay;
      var idx = replayState.plan.currentIndex || 0;
      var steps = replayState.plan.steps || [];
      if (nextAuto && idx >= steps.length - 1) {
        idx = 0;
      }
      syncReplayPlan(idx !== (replayState.plan.currentIndex || 0) ? "step" : "mode", idx, nextAuto);
    });
    btnScroll.addEventListener("click", function () {
      repositionReplaySpotlight(true);
    });

    replayState.host = host;
    replayState.shadow = shadow;
    replayState.refs = {
      hud: hud,
      spot: spot,
      spotBadge: spotBadge,
      fieldsLayer: fieldsLayer,
      epText: epText,
      metaText: metaText,
      statusBox: statusBox,
      statusText: statusText,
      fieldsBox: fieldsBox,
      stepsList: stepsList,
      btnAuto: btnAuto,
      btnPrev: btnPrev,
      btnNext: btnNext,
      btnScroll: btnScroll
    };

    if (!replayState.scrollBound) {
      replayState.scrollBound = true;
      window.addEventListener("scroll", function () { repositionReplaySpotlight(false); }, true);
      window.addEventListener("resize", function () { repositionReplaySpotlight(false); });
    }
    if (!replayState.trackTimer) {
      replayState.trackTimer = setInterval(function () {
        if (replayState.plan && replayState.plan.active) {
          repositionReplaySpotlight(false);
        }
      }, 220);
    }

    return replayState.refs;
  }

  function syncReplayPlan(action, nextIndex, nextAutoPlay) {
    var plan = replayState.plan;
    if (!plan) return;
    replayState.localSyncLockUntil = Date.now() + 900;
    if (action === "stop") {
      var pid = plan.planId;
      destroyReplayHud();
      apiCall(CONTROL + "/replay_plan", "POST", {
        action: "stop",
        planId: pid
      }).catch(function () {});
      return;
    }

    plan.currentIndex = nextIndex;
    plan.autoPlay = !!nextAutoPlay;
    renderReplayHudContents(plan, true);
    configureReplayAutoTimer(plan);

    apiCall(CONTROL + "/replay_plan", "POST", {
      action: action,
      planId: plan.planId,
      currentIndex: nextIndex,
      autoPlay: !!nextAutoPlay
    }).then(function (resp) {
      if (resp && resp.replayPlan && replayState.plan && replayState.plan.planId === resp.replayPlan.planId) {
        if (Date.now() >= replayState.localSyncLockUntil) {
          replayState.plan = resp.replayPlan;
          renderReplayHudContents(replayState.plan, false);
        }
      }
    }).catch(function () {});
  }

  function configureReplayAutoTimer(plan) {
    if (replayState.autoTimer) {
      clearInterval(replayState.autoTimer);
      replayState.autoTimer = null;
    }
    if (!plan || !plan.active || !plan.autoPlay) return;
    replayState.autoTimer = setInterval(function () {
      var p = replayState.plan;
      if (!p || !p.active || !p.autoPlay) {
        clearInterval(replayState.autoTimer);
        replayState.autoTimer = null;
        return;
      }
      var steps = p.steps || [];
      var cur = p.currentIndex || 0;
      if (cur < steps.length - 1) {
        syncReplayPlan("step", cur + 1, true);
      } else {
        syncReplayPlan("mode", cur, false);
      }
    }, 1200);
  }

  function repositionReplaySpotlight(forceScroll) {
    var refs = replayState.refs;
    var plan = replayState.plan;
    if (!refs || !plan || !plan.active) return;
    var steps = plan.steps || [];
    if (!steps.length) return;
    var idx = Math.max(0, Math.min(steps.length - 1, plan.currentIndex || 0));
    var step = steps[idx];
    var isFinal = (idx === steps.length - 1);
    var stepKey = (plan.planId || "") + ":" + idx;

    var loc = locateStepElement(step);
    refs.fieldsLayer.textContent = "";

    if (!loc.el || loc.isPageLoad) {
      refs.spot.style.display = "none";
      if (loc.isPageLoad) {
        refs.spotBadge.style.display = "block";
        refs.spotBadge.className = "spot-badge" + (isFinal ? " final" : "");
        refs.spotBadge.style.left = "14px";
        refs.spotBadge.style.top = "14px";
        refs.spotBadge.textContent = "Bước " + (idx + 1) + "/" + steps.length + ": Trang " + (step.pageUrl || "");
        refs.statusBox.className = "status-box " + (isFinal ? "final" : "ok");
        refs.statusText.textContent = isFinal
          ? "✓ Bước " + (idx + 1) + "/" + steps.length + " (page load) — Kích hoạt API tại đây"
          : "✓ Bước " + (idx + 1) + "/" + steps.length + ": Tải trang gốc";
      } else {
        refs.spotBadge.style.display = "none";
        refs.statusBox.className = "status-box warn";
        refs.statusText.textContent = "Mở bước trước hoặc tự thao tác để hiển thị phần tử này";
      }
      return;
    }

    if (forceScroll || replayState.lastScrolledStepKey !== stepKey) {
      replayState.lastScrolledStepKey = stepKey;
      try {
        loc.el.scrollIntoView({ block: "center", inline: "center", behavior: "smooth" });
      } catch (e) {}
    }

    var rect = loc.el.getBoundingClientRect();
    var left = rect.left + (loc.offX || 0);
    var top = rect.top + (loc.offY || 0);
    var w = Math.max(12, rect.width);
    var h = Math.max(12, rect.height);

    refs.spot.style.display = "block";
    refs.spot.className = "spot" + (isFinal ? " final" : "");
    refs.spot.style.left = (left - 4) + "px";
    refs.spot.style.top = (top - 4) + "px";
    refs.spot.style.width = (w + 8) + "px";
    refs.spot.style.height = (h + 8) + "px";

    var badgeTop = top >= 36 ? (top - 30) : (top + h + 8);
    var badgeLeft = Math.max(8, Math.min(window.innerWidth - 260, left));
    refs.spotBadge.style.display = "block";
    refs.spotBadge.className = "spot-badge" + (isFinal ? " final" : "");
    refs.spotBadge.style.left = badgeLeft + "px";
    refs.spotBadge.style.top = badgeTop + "px";
    var stepDesc = (step.kind ? (step.kind + " ") : "") + (step.label ? ('"' + step.label + '"') : (step.cssSelector || ""));
    refs.spotBadge.textContent = "Bước " + (idx + 1) + "/" + steps.length + ": " + stepDesc
      + (isFinal ? " — Kích hoạt API tại đây" : "");

    refs.statusBox.className = "status-box " + (isFinal ? "final" : "ok");
    refs.statusText.textContent = isFinal
      ? "🎯 Kích hoạt API tại đây (" + (step.label || step.kind || "trigger") + ")"
      : ("✓ Đã khoanh vùng bước " + (idx + 1) + "/" + steps.length + ": " + (step.label || step.kind || ""));

    // Highlight any captured filledFields inside the same root
    var ffs = Array.isArray(step.filledFields) ? step.filledFields : [];
    for (var fi = 0; fi < ffs.length; fi++) {
      var ff = ffs[fi];
      if (!ff || !ff.selector) continue;
      try {
        var fEl = loc.root && loc.root.querySelector ? loc.root.querySelector(ff.selector) : null;
        if (fEl && isVisible(fEl)) {
          var fr = fEl.getBoundingClientRect();
          var box = document.createElement("div");
          box.className = "field-box";
          box.style.left = (fr.left + (loc.offX || 0) - 2) + "px";
          box.style.top = (fr.top + (loc.offY || 0) - 2) + "px";
          box.style.width = (fr.width + 4) + "px";
          box.style.height = (fr.height + 4) + "px";
          refs.fieldsLayer.appendChild(box);
        }
      } catch (e) {}
    }
  }

  function renderReplayHudContents(plan, forceScroll) {
    var refs = ensureReplayHudMounted();
    var steps = Array.isArray(plan.steps) ? plan.steps : [];
    var idx = Math.max(0, Math.min(Math.max(0, steps.length - 1), plan.currentIndex || 0));

    refs.epText.textContent = plan.targetEndpoint || "(Target API)";
    refs.metaText.textContent = (plan.targetTitle ? (plan.targetTitle + " • ") : "")
      + "Bước " + (steps.length > 0 ? (idx + 1) : 0) + "/" + steps.length;

    refs.btnAuto.textContent = plan.autoPlay ? "⏸ Tạm dừng" : "▶ Tự chạy";
    refs.btnPrev.disabled = (idx <= 0);
    refs.btnNext.disabled = (idx >= steps.length - 1);

    // Rebuild step list cleanly
    refs.stepsList.textContent = "";
    for (var i = 0; i < steps.length; i++) {
      (function (stepIdx, s) {
        var row = document.createElement("button");
        row.type = "button";
        row.className = "step-item" + (stepIdx === idx ? " active" : "");
        var num = document.createElement("span");
        num.className = "step-num";
        num.textContent = String(stepIdx + 1);
        var lbl = document.createElement("span");
        lbl.className = "step-lbl";
        var desc = (s.kind || "step") + (s.label ? (' "' + s.label + '"') : "");
        if (stepIdx === steps.length - 1) desc += " 🎯";
        lbl.textContent = desc;
        row.appendChild(num);
        row.appendChild(lbl);
        row.addEventListener("click", function () {
          syncReplayPlan("step", stepIdx, false);
        });
        refs.stepsList.appendChild(row);
      })(i, steps[i]);
    }

    // Show filledFields chips for active step
    refs.fieldsBox.textContent = "";
    var curStep = steps[idx];
    var ffs = (curStep && Array.isArray(curStep.filledFields)) ? curStep.filledFields : [];
    for (var j = 0; j < ffs.length && j < 6; j++) {
      var chip = document.createElement("span");
      chip.className = "field-chip";
      chip.textContent = (ffs[j].label || "input") + " = " + (ffs[j].value || "");
      refs.fieldsBox.appendChild(chip);
    }

    repositionReplaySpotlight(forceScroll);
  }

  function updateReplayHud(plan) {
    if (!plan || !plan.active) {
      if (replayState.host) {
        destroyReplayHud();
      }
      return;
    }
    if (Date.now() < replayState.localSyncLockUntil && replayState.plan && replayState.plan.planId === plan.planId) {
      return;
    }
    var key = [plan.planId || "", plan.currentIndex || 0, !!plan.autoPlay, (plan.steps || []).length].join("|");
    var prevKey = replayState.lastRenderedKey;
    replayState.plan = plan;
    if (key !== prevKey || !replayState.host) {
      var stepChanged = !prevKey || prevKey.split("|")[1] !== String(plan.currentIndex || 0) || prevKey.split("|")[0] !== (plan.planId || "");
      replayState.lastRenderedKey = key;
      renderReplayHudContents(plan, stepChanged);
      configureReplayAutoTimer(plan);
    }
  }

  function handleStatePoll(st) {
    currentConfig = st;
    applyServiceWorkerBypassGuard(st);
    installHumanContextTracker(document, "");
    updateReplayHud(st.replayPlan);
    if (st.status === "STOPPED" || st.status === "PAUSED") {
      disconnectDomMutationObserver();
    } else {
      installDomMutationObserver();
    }

    if (st.status === "RUNNING" && st.allowAutoRequests) {
      runCrawlPass();
    } else if (st.status === "RUNNING") {
      if ((st.pendingInterceptCount || 0) > 0) {
        reportTelemetry("WAITING_APPROVAL", 0, 0, false);
        updateBadge("Đang chờ bạn duyệt trên Burp (" + (st.pendingInterceptCount | 0) + " mục)...", "#b45309");
        return;
      }
      if (lastManualPageUrl !== location.href) {
        lastManualPageUrl = location.href;
        var pageLoadPath = formatActionPath(location.href, "page load", "", st);
        apiCall(CONTROL + "/context", "POST", {
          actionPath: pageLoadPath,
          kind: "page load",
          uiAction: "navigate",
          label: "",
          pageUrl: location.href
        }).catch(function () {});
      }
      reportTelemetry("IDLE", 0, 0, false);
      updateBadge("Thủ công (Chưa bật tự gửi): tự bấm trên trang", "#b45309");
    } else {
      reportTelemetry(st.status === "PAUSED" ? "PAUSED" : "IDLE", 0, 0, false);
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
    }, 350);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
