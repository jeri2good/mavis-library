export function extractPodcastChapter(doc, toc, currentNode, fallbackLabel) {
  const body = doc.getElementsByTagName('body')[0] || doc.documentElement;
  const positions = new Map();
  const parts = [];
  const blocks = new Set(['p', 'div', 'section', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'li', 'br', 'blockquote', 'tr']);
  const walk = (node) => {
    positions.set(node, parts.length);
    if (node.nodeType === 3) { parts.push(node.nodeValue); return; }
    if (node.nodeType !== 1) return;
    const tag = node.localName?.toLowerCase();
    if (['script', 'style', 'noscript'].includes(tag) || node.hasAttribute('hidden') || node.getAttribute('aria-hidden') === 'true') return;
    if (blocks.has(tag)) parts.push('\n');
    for (let child = node.firstChild; child; child = child.nextSibling) walk(child);
    if (blocks.has(tag)) parts.push('\n');
  };
  walk(body);
  const boundaries = toc.map((entry) => {
    const fragment = entry.href.split('#')[1];
    let anchor;
    try { anchor = fragment ? doc.getElementById(decodeURIComponent(fragment)) : body; } catch { return null; }
    return anchor && positions.has(anchor) ? { ...entry, position: positions.get(anchor) } : null;
  }).filter(Boolean).sort((a, b) => a.position - b.position);
  let node = currentNode;
  while (node && !positions.has(node)) node = node.parentNode;
  const current = node ? positions.get(node) : 0;
  const start = node ? boundaries.filter((entry) => entry.position <= current).at(-1) : null;
  const end = start && boundaries.find((entry) => entry.position > start.position);
  const text = parts.slice(start?.position || 0, end?.position ?? parts.length).join('')
    .replace(/[ \t\r\f]+/g, ' ').replace(/ *\n */g, '\n').replace(/\n{2,}/g, '\n').trim();
  return { label: start?.label || fallbackLabel, text };
}
