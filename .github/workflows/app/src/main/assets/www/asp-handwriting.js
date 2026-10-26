/* asp-handwriting.js — APS handwriting bridge shim (generated-app asset).
   window.ASPHandwriting.recognize(strokeData) -> Promise<string>   (ML Kit)
   window.ASPHandwriting.openSystemInput()     -> Promise<string>   (IME fallback)
   When the native bridge is absent (e.g. inside the APS preview), recognize()
   falls back to the APS demo engine over postMessage. */
(function () {
  'use strict';
  if (window.ASPHandwriting) return;
  var pending = {}, seq = 0, resultCbs = [], engine = "pending", modelReady = false;
  function resolve(reqId, ok, value) {
    var p = pending[reqId]; if (!p) return;
    delete pending[reqId];
    if (ok) p.resolve(value); else p.reject(new Error(value || "HANDWRITING_FAILED"));
  }
  function request(method, payload) {
    return new Promise(function (resolve, reject) {
      var reqId = "hw" + (++seq) + "_" + Date.now();
      pending[reqId] = { resolve: resolve, reject: reject };
      var to = setTimeout(function () { resolve(reqId, false, "HANDWRITING_TIMEOUT"); }, 20000);
      pending[reqId].timer = to;
      if (window.ASPHandwritingNative) {
        try { window.ASPHandwritingNative[method](reqId, payload); return; } catch (e) { reject(e); return; }
      }
      window.parent.postMessage({ type: "asp-hw-request", reqId: reqId, method: method, payload: payload }, "*");
    });
  }
  window.__aspHandwritingResolve = function (reqId, ok, value) {
    var p = pending[reqId]; if (p && p.timer) clearTimeout(p.timer);
    resolve(reqId, ok, value);
  };
  window.__aspHandwritingEngine = function (info) {
    engine = (info && info.engine) || "none";
    modelReady = !!(info && info.modelReady);
    for (var i = 0; i < resultCbs.length; i++) resultCbs[i]({ engine: engine, modelReady: modelReady });
    try { document.dispatchEvent(new CustomEvent("asp-handwriting-engine", { detail: { engine: engine, modelReady: modelReady } })); } catch (e) {}
  };
  window.__aspHandwritingResult = function (text) {
    for (var i = 0; i < resultCbs.length; i++) resultCbs[i]({ text: text });
    try { document.dispatchEvent(new CustomEvent("asp-handwriting-result", { detail: { text: text } })); } catch (e) {}
  };
  window.addEventListener("message", function (ev) {
    var d = ev.data;
    if (!d) return;
    if (d.type === "asp-hw-response") window.__aspHandwritingResolve(d.reqId, d.ok, d.value);
    if (d.type === "asp-hw-engine") window.__aspHandwritingEngine(d.info);
  });
  window.ASPHandwriting = {
    version: "1.0",
    engine: function () { return engine; },
    modelReady: function () { return modelReady; },
    onResult: function (cb) { resultCbs.push(cb); return function () { var i = resultCbs.indexOf(cb); if (i >= 0) resultCbs.splice(i, 1); }; },
    recognize: function (strokeData) {
      var ink = strokeData && strokeData.strokes ? strokeData : { strokes: strokeData || [] };
      return request("recognizeRequest", JSON.stringify(ink));
    },
    openSystemInput: function () { return request("openSystemInputRequest", ""); }
  };
})();
