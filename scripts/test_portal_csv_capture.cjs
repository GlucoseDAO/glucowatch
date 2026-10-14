// Regression tests for real failure patterns, without credentials or a network.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(require('node:path').join(__dirname,'../phone/src/main/assets/portal-csv-capture.js'),'utf8')
  .replace('__BRIDGE_NAME__','"nativeCsv"').replace('__MAX_BYTES__',String(20*1024*1024));
const csv = 'Date,Time,Bolus Volume Delivered (U)\n14/10/2026,12:00:00,1.5\n';

function environment(fetchImpl, overrides={}) {
  const messages=[],requests=[],events={};
  const native={postMessage(data) {
    messages.push(JSON.parse(data));
    queueMicrotask(()=>native.onmessage && native.onmessage({data:'ok'}));
  }};
  class Anchor {
    constructor(href) { this.href=href; this.textContent='Download CSV'; this.download='data.csv'; this.originalClicks=0; }
    hasAttribute(name) { return name==='download'; }
    getAttribute() { return null; }
    closest(selector) { return selector.includes('a') ? this : null; }
    click() { this.originalClicks++; }
  }
  class Form {
    constructor(action,method='post') { this.action=action;this.method=method;this.enctype='application/x-www-form-urlencoded';this.fields=[];this.submitted=0; }
    querySelector() { return null; }
    submit() { this.submitted++; }
  }
  class BrowserFormData extends FormData {
    constructor(form) { super();for(const [k,v] of form.fields) this.append(k,v); }
  }
  class Xhr {
    constructor() { this.listeners={};this.responseType='';this.responseText=csv;this.status=200;this.responseURL='https://clarity.dexcom.eu/api/export'; }
    addEventListener(name,fn) { this.listeners[name]=fn; }
    getResponseHeader(name) { return name==='Content-Type'?'text/csv':null; }
    send() { queueMicrotask(()=>this.listeners.load()); }
  }
  class BrowserURL extends URL {}
  BrowserURL.createObjectURL=blob=>'blob:https://carelink.minimed.eu/'+Math.random();
  BrowserURL.revokeObjectURL=()=>{};
  const document={addEventListener(name,fn) { (events[name] ||= []).push(fn); }};
  const context={nativeCsv:native,Blob,Response,Request,URL:BrowserURL,URLSearchParams,FormData:BrowserFormData,
    HTMLAnchorElement:Anchor,HTMLFormElement:Form,XMLHttpRequest:Xhr,document,
    location:{origin:'https://clarity.dexcom.eu',href:'https://clarity.dexcom.eu/reports'},
    fetch:async(...args)=>{ requests.push(args);return fetchImpl ? fetchImpl(...args) : new Response('',{status:401}); },
    Uint8Array,Date,Math,setTimeout,clearTimeout,btoa:s=>Buffer.from(s,'binary').toString('base64'),...overrides};
  context.window=context;context.top=context;
  vm.runInNewContext(source,context);
  const arm=(label='Download CSV',ariaLabel=null)=>{
    const button={textContent:label,closest(selector) { return selector==='a'?null:this; },getAttribute(name){return name==='aria-label'?ariaLabel:null;},hasAttribute(){return false;}};
    for(const fn of events.click || []) fn({isTrusted:true,target:button,preventDefault(){}});
  };
  return {context,messages,requests,arm,Anchor,Form,Xhr};
}
async function waitFor(predicate) {
  const until=Date.now()+2000;
  while(Date.now()<until) { if(predicate())return;await new Promise(r=>setTimeout(r,5)); }
  throw Error('CSV capture did not finish');
}
function captured(env) {
  return Buffer.concat(env.messages.filter(m=>m.kind==='chunk').map(m=>Buffer.from(m.chunk,'base64'))).toString('utf8');
}

(async()=>{
  {
    const e=environment();e.arm();
    const url=e.context.URL.createObjectURL(new Blob([csv],{type:'text/csv'}));
    const a=new e.Anchor(url);a.click();e.context.URL.revokeObjectURL(url);
    await waitFor(()=>e.messages.some(m=>m.kind==='done'));
    assert.equal(captured(e),csv);assert.equal(e.requests.length,0);assert.equal(a.originalClicks,0);
    console.log('PASS: immediately revoked blob is retained without fetching its expired URL');
  }
  {
    class ChildURL extends URL {}
    ChildURL.createObjectURL=()=> 'blob:https://clarity.dexcom.eu/blank-child';
    ChildURL.revokeObjectURL=()=>{};
    class ChildAnchor { click(){throw Error('Expired child download must be intercepted');} }
    const child={document:{},URL:ChildURL,HTMLAnchorElement:ChildAnchor};
    const e=environment(null,{open:()=>child});e.arm();
    const popup=e.context.open('about:blank');
    const anchor=new popup.HTMLAnchorElement();
    anchor.href=popup.URL.createObjectURL(new Blob([csv],{type:'text/csv'}));anchor.click();
    popup.URL.revokeObjectURL(anchor.href);
    await waitFor(()=>e.messages.some(m=>m.kind==='done'));
    assert.equal(captured(e),csv);assert.equal(e.requests.length,0);
    console.log('PASS: blank child without a native bridge shares its retained CSV with the parent');
  }
  {
    const child={};Object.defineProperty(child,'document',{get(){throw Error('Cross origin');}});
    const e=environment(null,{open:()=>child});
    assert.equal(e.context.open('https://outside.invalid'),child);
    assert.equal(child.__glucoCsvCapture,undefined);
    console.log('PASS: cross-origin child is not modified');
  }
  {
    const e=environment();e.arm(' ', 'Download CSV');
    const url=e.context.URL.createObjectURL(new Blob([csv],{type:'application/vnd.ms-excel'}));
    new e.Anchor(url).click();await waitFor(()=>e.messages.some(m=>m.kind==='done'));
    assert.equal(captured(e),csv);
    console.log('PASS: icon export labels and Excel-labelled CSV blobs are recognized');
  }
  {
    const e=environment(async()=>new Response(csv,{headers:{'Content-Type':'text/csv'}}));e.arm();
    const options={method:'POST',headers:{Authorization:'Bearer fixture-only'},body:'selected-dates=fixture'};
    const response=await e.context.fetch('https://clarity.dexcom.eu/api/export',options);
    assert.equal(await response.text(),csv);
    await waitFor(()=>e.messages.some(m=>m.kind==='done'));
    assert.equal(captured(e),csv);assert.equal(e.requests.length,1);assert.equal(e.requests[0][1],options);
    assert.ok(!JSON.stringify(e.messages).includes('fixture-only'));
    console.log('PASS: original authenticated POST response is captured without replay or exposing authentication');
  }
  {
    const e=environment(async()=>new Response(csv,{headers:{'Content-Type':'text/csv'}}));e.arm();
    const form=new e.Form('https://clarity.dexcom.eu/api/subject/fixture/export');
    form.fields=[['accessToken','fixture-only'],['dates','2026-10-13/2026-10-14']];form.submit();
    await waitFor(()=>e.messages.some(m=>m.kind==='done'));
    assert.equal(form.submitted,0);assert.equal(e.requests.length,1);assert.equal(e.requests[0][1].method,'POST');
    assert.equal(e.requests[0][1].body.get('accessToken'),'fixture-only');assert.equal(captured(e),csv);
    assert.ok(!JSON.stringify(e.messages).includes('fixture-only'));
    console.log('PASS: Clarity export form retains its POST body inside the browser');
  }
  {
    const e=environment();const login=new e.Form('https://clarity.dexcom.eu/login');e.arm();login.submit();
    assert.equal(login.submitted,1);assert.equal(e.requests.length,0);assert.equal(e.messages.length,0);
    console.log('PASS: login forms are never intercepted');
  }
  {
    const e=environment(async()=>new Response(csv,{headers:{'Content-Type':'text/csv'}}));
    await e.context.fetch('https://clarity.dexcom.eu/api/export');await new Promise(r=>setTimeout(r,20));
    assert.equal(e.messages.length,0);
    console.log('PASS: background CSV responses are not collected without a download gesture');
  }
  {
    const e=environment();e.arm();const xhr=new e.Xhr();xhr.send();
    await waitFor(()=>e.messages.some(m=>m.kind==='done'));
    assert.equal(captured(e),csv);
    console.log('PASS: authenticated XHR response is captured in its original request context');
  }
  {
    const e=environment();await e.context.__glucoCsvCapture.captureBlob(new Blob(['<html>Sign in</html>'],{type:'text/plain'}));
    assert.ok(e.messages.some(m=>m.kind==='error'&&m.reason==='format'));assert.ok(!e.messages.some(m=>m.kind==='start'));
    console.log('PASS: login HTML cannot be saved as a CSV');
  }
})().catch(e=>{console.error(e);process.exitCode=1;});
