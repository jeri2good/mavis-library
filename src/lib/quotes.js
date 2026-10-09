// Copy and share quotations with their source, from any book or the Bible.
import { toast } from './ui.js';

export function formatQuote(text, citation) {
  const t = String(text || '').replace(/\s+/g, ' ').trim();
  return `“${t}”\n— ${citation}`;
}

export async function copyQuote(text, citation) {
  const s = formatQuote(text, citation);
  try {
    await navigator.clipboard.writeText(s);
    toast('Quote copied.');
    return true;
  } catch {
    toast('Copying isn’t allowed here. Use your device’s copy option instead.');
    return false;
  }
}

export async function shareQuote(text, citation) {
  const s = formatQuote(text, citation);
  if (navigator.share) {
    try { await navigator.share({ text: s }); return true; }
    catch (err) { if (err?.name === 'AbortError') return false; }
  }
  return copyQuote(text, citation);
}
