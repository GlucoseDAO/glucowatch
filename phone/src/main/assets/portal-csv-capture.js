/* Read-only CSV capture. No login fields, storage tokens or request headers are read.
 * Retain generated CSV blobs before revocation and the original fetch/XHR CSV response.
 * Native messages are available only in the selected provider's permitted HTTPS frames.
 */
(() => {
  'use strict';
  const native = window[__BRIDGE_NAME__];
  const controlName = '__glucoCsvCapture';
  if (!native || window[controlName]) return;
  const maxBytes = __MAX_BYTES__;
  const cache = new Map();
  const originalFetch = window.fetch.bind(window);
  let busy = false, armedAt = 0;

  function host() {
    // Same-origin iframe/popup exports survive destruction of the short-lived child.
    try {
      if (window.top !== window && window.top.document && window.top[controlName])
        return window.top[controlName];
      if (window.opener && window.opener.document && window.opener[controlName])
        return window.opener[controlName];
    } catch (_) {}
    return window[controlName];
  }
  function candidate(blob) {
    return blob && blob.size > 0 && blob.size <= maxBytes &&
      (!blob.type || /csv|text\/plain|octet-stream|application\/vnd\.ms-excel/i.test(blob.type));
  }
  function remember(url, blob) {
    if (!candidate(blob)) return;
    // The wrapper and its async methods must belong to this surviving realm.
    // A Blob from a closed child can otherwise leave arrayBuffer() unresolved.
    cache.set(url, Promise.resolve(new Blob([blob], {type:blob.type})));
    while (cache.size > 3) cache.delete(cache.keys().next().value);
  }
  function rememberPromise(url, promise) {
    cache.set(url, promise);
    while (cache.size > 3) cache.delete(cache.keys().next().value);
  }
  function csvHeaders(type, disposition) {
    return /csv/i.test(type || '') || /\.csv(?:["';\s]|$)/i.test(disposition || '');
  }
  async function boundedBlob(response) {
    if (Number(response.headers.get('Content-Length')) > maxBytes) throw Error('size');
    if (!response.body || !response.body.getReader) {
      const blob=await response.blob();
      if (blob.size>maxBytes) throw Error('size');
      return blob;
    }
    const reader=response.body.getReader(), chunks=[];
    let total=0;
    try {
      while (true) {
        const part=await reader.read(); if (part.done) break;
        total+=part.value.byteLength;
        if (total>maxBytes) throw Error('size');
        chunks.push(part.value);
      }
      return new Blob(chunks,{type:response.headers.get('Content-Type') || 'text/csv'});
    } finally { reader.cancel().catch(()=>{}); }
  }
  function arm(event) {
    if (!event.isTrusted) return;
    // Only remember an export/download gesture, not the contents of any form.
    const element = event.target.closest && event.target.closest('button,a,input[type=button],input[type=submit],[role=button]');
    if (element && (/export|download|csv/i.test([element.textContent,element.getAttribute('aria-label'),
                     element.getAttribute('title'),element.value].filter(Boolean).join(' ')) ||
                    element.hasAttribute('download'))) armedAt = Date.now();
  }
  document.addEventListener('click', arm, true);

  const create = URL.createObjectURL.bind(URL);
  URL.createObjectURL = function(blob) {
    const url = create(blob);
    if (candidate(blob)) host().remember(url, blob);
    return url;
  };
  // URL.revokeObjectURL stays untouched. Keeping the Blob, rather than its URL,
  // lets normal website cleanup run without breaking the selected CSV download.
  window.fetch = function(...args) {
    return originalFetch(...args).then(response => {
      if (response.ok && csvHeaders(response.headers.get('Content-Type'), response.headers.get('Content-Disposition'))) {
        const clone = response.clone();
        const url = response.url || (typeof args[0] === 'string' ? args[0] : args[0].url);
        if (!(Number(clone.headers.get('Content-Length')) > maxBytes)) {
          const promise = boundedBlob(clone);
          promise.catch(()=>{});
          host().rememberPromise(url, promise);
          if (host().armed()) promise.then(blob => host().captureBlob(blob)).catch(() => {});
        }
      }
      return response;
    });
  };
  const sendXhr = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(...args) {
    this.addEventListener('load', () => {
      try {
        if (this.status < 200 || this.status >= 300 || !csvHeaders(this.getResponseHeader('Content-Type'), this.getResponseHeader('Content-Disposition'))) return;
        const blob = this.responseType === 'blob' ? this.response :
          new Blob([this.responseType === 'arraybuffer' ? this.response : this.responseText], {type:'text/csv'});
        host().remember(this.responseURL, blob);
        if (host().armed()) host().captureBlob(blob);
      } catch (_) {}
    }, {once:true});
    return sendXhr.apply(this,args);
  };
  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function() {
    const receiver = host();
    if (receiver.armed() && receiver.has(this.href)) { receiver.capture(this.href); return; }
    return click.call(this);
  };
  document.addEventListener('click', event => {
    const a = event.target.closest && event.target.closest('a');
    if (a && host().has(a.href)) { event.preventDefault(); host().capture(a.href); }
  }, true);

  // Clarity can submit its CSV as a POST form. Replaying the address as GET loses
  // the export request's body/session. Serialize only that exact export form in
  // the browser; none of its fields or authentication values enter native code.
  function isCsvForm(form) {
    const action=new URL(form.action,location.href);
    return host().armed() && action.origin===location.origin &&
      /^\/api\/subject\/[^/]+\/export\/?$/i.test(action.pathname) &&
      !form.querySelector('input[type=password],input[type=file],input[name=username],input[name=password]');
  }
  async function captureForm(form,submitter) {
    try {
      const action=new URL(form.action,location.href);
      const data=submitter ? new FormData(form,submitter) : new FormData(form);
      let body=data, url=action.href;
      const method=(form.method || 'get').toUpperCase();
      const headers={};
      if (method==='GET') {
        for (const [name,value] of data) action.searchParams.append(name,String(value));
        url=action.href; body=undefined;
      } else if (form.enctype!=='multipart/form-data') {
        body=new URLSearchParams(data);
        headers['Content-Type']='application/x-www-form-urlencoded;charset=UTF-8';
      }
      const response=await originalFetch(url,{method,body,headers,credentials:'include'});
      if (!response.ok) {
        native.postMessage(JSON.stringify({id:'',kind:'error',reason:'http',status:response.status})); return;
      }
      await host().captureBlob(await boundedBlob(response));
    } catch (_) { native.postMessage(JSON.stringify({id:'',kind:'error',reason:'unavailable'})); }
  }
  const submit=HTMLFormElement.prototype.submit;
  HTMLFormElement.prototype.submit=function() {
    if (isCsvForm(this)) { captureForm(this); return; }
    return submit.call(this);
  };
  document.addEventListener('submit',event => {
    if (event.target instanceof HTMLFormElement && isCsvForm(event.target)) {
      event.preventDefault(); captureForm(event.target,event.submitter);
    }
  },true);

  function send(id, kind, extra={}) {
    return new Promise((resolve,reject) => {
      const timer=setTimeout(() => reject(Error('timeout')),15000);
      native.onmessage = event => {
        clearTimeout(timer);
        event.data === 'ok' ? resolve() : reject(Error('cancelled'));
      };
      native.postMessage(JSON.stringify({id,kind,...extra}));
    });
  }
  async function captureBlob(blob) {
    if (busy) return;
    busy = true;
    const id = String(Date.now()) + '-' + Math.random().toString(16).slice(2);
    try {
      if (!candidate(blob)) throw Error(blob && blob.size > maxBytes ? 'size' : 'format');
      blob = new Blob([blob], {type:blob.type});
      const head = await blob.slice(0,65536).text();
      if (!/(?:\bDate\b.*\bTime\b|Timestamp\s*\()/i.test(head) || /^\s*(?:<|%PDF)/.test(head)) throw Error('format');
      await send(id,'start',{size:blob.size});
      for (let offset=0;offset<blob.size;offset+=49152) {
        const bytes=new Uint8Array(await blob.slice(offset,offset+49152).arrayBuffer());
        await send(id,'chunk',{chunk:btoa(String.fromCharCode.apply(null,bytes))});
      }
      await send(id,'done');
      cache.clear(); armedAt=0;
    } catch (error) {
      native.postMessage(JSON.stringify({id,kind:'error',reason:['size','format','timeout','cancelled'].includes(error.message) ? error.message : 'unavailable'}));
    } finally { busy=false; }
  }
  async function capture(url) {
    if (busy) return;
    try {
      const saved=cache.get(url);
      if (saved) { await captureBlob(await saved); return; }
      const parsed=new URL(url,location.href);
      if (parsed.protocol !== 'https:' && parsed.protocol !== 'blob:') throw Error('unavailable');
      const response=await originalFetch(url,{credentials:'include'});
      if (!response.ok) {
        native.postMessage(JSON.stringify({id:'',kind:'error',reason:'http',status:response.status}));
        return;
      }
      await captureBlob(await boundedBlob(response));
    } catch (_) { native.postMessage(JSON.stringify({id:'',kind:'error',reason:'unavailable'})); }
  }
  Object.defineProperty(window,controlName,{value:{remember,rememberPromise,has:url=>cache.has(url),
    armed:()=>Date.now()-armedAt<120000,capture,captureBlob},configurable:false});

  // Initial about:blank children inherit their creator's origin but have no
  // document-start script yet. Capture their export before immediate revocation
  // or window.close()/iframe removal. Access to document enforces same origin.
  function prepareChild(child) {
    try {
      if (!child || !child.document || child[controlName]) return child;
      const receiver=host();
      Object.defineProperty(child,controlName,{value:receiver,configurable:false});
      const createChild=child.URL.createObjectURL.bind(child.URL);
      child.URL.createObjectURL=function(blob) {
        const url=createChild(blob); receiver.remember(url,blob); return url;
      };
      const clickChild=child.HTMLAnchorElement.prototype.click;
      child.HTMLAnchorElement.prototype.click=function() {
        if (receiver.armed() && receiver.has(this.href)) { receiver.capture(this.href); return; }
        return clickChild.call(this);
      };
    } catch (_) {} // A cross-origin child stays untouched.
    return child;
  }
  const open=window.open;
  if (open) window.open=function(...args) { return prepareChild(open.apply(this,args)); };
  if (typeof HTMLIFrameElement !== 'undefined') {
    const descriptor=Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype,'contentWindow');
    if (descriptor && descriptor.get && descriptor.configurable) {
      Object.defineProperty(HTMLIFrameElement.prototype,'contentWindow',{
        ...descriptor,get() { return prepareChild(descriptor.get.call(this)); }
      });
    }
  }
})();
