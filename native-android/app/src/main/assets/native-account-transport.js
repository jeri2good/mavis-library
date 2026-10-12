(() => {
  if (window.MavisNativeAccount) return;
  const site = new URL(window.MavisNativeApiBase);
  delete window.MavisNativeApiBase;
  let token = '';
  const original = window.fetch;
  const paths = new Set(['/api/assistant', '/api/tts', '/api/rank', '/api/study', '/api/features']);
  const trusted = url => url.origin === site.origin && paths.has(url.pathname);
  window.MavisNativeAccount = Object.freeze({setToken(value) { token = String(value || ''); }});
  window.fetch = async function(input, init) {
    const request = new Request(input, init);
    const url = new URL(request.url);
    if (!trusted(url)) return original.call(this, request);
    const currentToken = token;
    const headers = new Headers(request.headers);
    if (currentToken) headers.set('Authorization', `Bearer ${currentToken}`);
    else headers.delete('Authorization');
    const result = await original.call(this, new Request(request, {headers}));
    if (url.pathname !== '/api/features' || !result.ok || !currentToken) return result;
    try {
      const flags = await result.clone().json();
      if (flags.owner) return result;
      const access = await original.call(this, new Request(new URL('/api/subscription/status', site), {
        headers: {Authorization: `Bearer ${currentToken}`, Accept: 'application/json'}, cache: 'no-store', redirect: 'error'
      }));
      if (!access.ok || token !== currentToken) return result;
      const sub = await access.json();
      if (sub.state !== 'active' || sub.expiresAt != null && sub.expiresAt <= Date.now()) return result;
      const responseHeaders = new Headers(result.headers);
      responseHeaders.delete('content-length');
      responseHeaders.set('cache-control', 'no-store');
      return new Response(JSON.stringify({...flags, owner: true, nativeSubscriber: true}), {status: result.status, headers: responseHeaders});
    } catch { return result; }
  };
  const open = XMLHttpRequest.prototype.open;
  const send = XMLHttpRequest.prototype.send;
  const setHeader = XMLHttpRequest.prototype.setRequestHeader;
  const urls = new WeakMap();
  XMLHttpRequest.prototype.open = function(method, url, ...args) {
    urls.set(this, new URL(url, location.href));
    return open.call(this, method, url, ...args);
  };
  XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
    if (trusted(urls.get(this) || new URL(location.href)) && String(name).toLowerCase() === 'authorization') return;
    return setHeader.call(this, name, value);
  };
  XMLHttpRequest.prototype.send = function(...args) {
    if (trusted(urls.get(this) || new URL(location.href)) && token) setHeader.call(this, 'Authorization', `Bearer ${token}`);
    return send.apply(this, args);
  };
})();
