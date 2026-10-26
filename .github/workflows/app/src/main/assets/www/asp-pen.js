/* asp-pen.js — APS S Pen hover bridge shim (generated-app asset).
   Defines window.ASPPen and converts native pen-hover payloads into
   W3C Pointer Events (pointerType:"pen") + the "asp-pen-hover" custom event. */
(function () {
  'use strict';
  if (window.ASPPen) return;
  var cfg = { pointerEvents: true, customEvent: true, throttleMs: 16 };
  try { if (window.__ASPPEN_CFG) { for (var k in window.__ASPPEN_CFG) cfg[k] = window.__ASPPEN_CFG[k]; } } catch (e) {}
  var hoverMoveCbs = [], hoverEnterCbs = [], hoverLeaveCbs = [];
  var last = null, lastChain = null, lastTs = 0;
  function chainOf(el) { var c = [], n = el; while (n && n.nodeType === 1) { c.push(n); n = n.parentElement; } return c; }
  function fire(type, target, p, related) {
    if (!cfg.pointerEvents || !target || typeof PointerEvent !== "function") return;
    try {
      target.dispatchEvent(new PointerEvent(type, {
        pointerId: 7, pointerType: "pen", isPrimary: false,
        clientX: p.x, clientY: p.y, screenX: p.x, screenY: p.y,
        buttons: 0, button: -1, pressure: p.pressure || 0,
        tiltX: p.tiltX || 0, tiltY: p.tiltY || 0, width: 1, height: 1,
        bubbles: true, cancelable: true, composed: true, relatedTarget: related || null
      }));
    } catch (e) {}
  }
  function hoverMove(p) {
    var now = p.t || Date.now();
    if (cfg.throttleMs && now - lastTs < cfg.throttleMs && last && p.type !== "exit") return;
    lastTs = now;
    var el = document.elementFromPoint(p.x, p.y);
    if (!el) el = document.documentElement;
    var chain = chainOf(el);
    if (lastChain && lastChain[0] !== chain[0]) {
      fire("pointerout", lastChain[0], p, el);
      for (var i = 0; i < lastChain.length; i++) if (chain.indexOf(lastChain[i]) < 0) fire("pointerleave", lastChain[i], p, el);
    }
    if (!lastChain || lastChain[0] !== chain[0]) {
      for (var j = 0; j < chain.length; j++) if (!lastChain || lastChain.indexOf(chain[j]) < 0) fire("pointerenter", chain[j], p, (lastChain && lastChain[0]) || null);
      fire("pointerover", chain[0], p, (lastChain && lastChain[0]) || null);
      for (var k2 = 0; k2 < hoverEnterCbs.length; k2++) { try { hoverEnterCbs[k2](p, el); } catch (e2) {} }
    }
    fire("pointermove", chain[0], p, null);
    if (cfg.customEvent) {
      try {
        document.dispatchEvent(new CustomEvent("asp-pen-hover", {
          bubbles: true, cancelable: false, composed: true,
          detail: { x: p.x, y: p.y, pressure: p.pressure || 0, tiltX: p.tiltX || 0, tiltY: p.tiltY || 0, timestamp: p.t || now, source: "pen" }
        }));
      } catch (e3) {}
    }
    last = p; lastChain = chain;
    for (var m = 0; m < hoverMoveCbs.length; m++) { try { hoverMoveCbs[m](p, el); } catch (e4) {} }
  }
  function hoverExit(p) {
    if (!lastChain || !lastChain.length) return;
    fire("pointerout", lastChain[0], p, null);
    for (var i = 0; i < lastChain.length; i++) fire("pointerleave", lastChain[i], p, null);
    for (var j = 0; j < hoverLeaveCbs.length; j++) { try { hoverLeaveCbs[j](p); } catch (e) {} }
    lastChain = null;
  }
  window.__aspPenDispatch = function (payload) {
    var p = (typeof payload === "string") ? JSON.parse(payload) : payload;
    p.type = p.type || "move";
    if (p.type === "exit") hoverExit(p); else hoverMove(p);
  };
  window.addEventListener("message", function (ev) {
    var d = ev.data;
    if (d && d.type === "asp-pen-hover" && d.payload) window.__aspPenDispatch(d.payload);
  });
  window.ASPPen = {
    supported: true, pointerType: "pen", version: "1.0",
    last: function () { return last; },
    configure: function (o) { if (o) { for (var k in o) cfg[k] = o[k]; } },
    onHoverMove: function (cb) { hoverMoveCbs.push(cb); return function () { var i = hoverMoveCbs.indexOf(cb); if (i >= 0) hoverMoveCbs.splice(i, 1); }; },
    onHoverEnter: function (cb) { hoverEnterCbs.push(cb); return function () { var i = hoverEnterCbs.indexOf(cb); if (i >= 0) hoverEnterCbs.splice(i, 1); }; },
    onHoverLeave: function (cb) { hoverLeaveCbs.push(cb); return function () { var i = hoverLeaveCbs.indexOf(cb); if (i >= 0) hoverLeaveCbs.splice(i, 1); }; }
  };
})();
