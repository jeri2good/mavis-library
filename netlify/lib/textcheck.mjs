// Spoiler guard for AI reading tools. Models sometimes "know" a famous book and
// name people the reader hasn't met yet, even when told to use only the text.
// These checks compare proper names in the AI's answer against the text the
// reader has actually reached, and remove what isn't supported by it.

const HONORIFIC = /^(mr|mrs|miss|ms|dr|lady|lord|sir|madam|madame|captain|capt|colonel|col|uncle|aunt|father|mother|brother|sister|saint|st|king|queen|prince|princess|master|mistress|professor|rev|reverend)$/i;
// Capitalized words that aren't names, or are fine without appearing in the text.
const COMMON = new Set(['i', 'the', 'a', 'an', 'he', 'she', 'it', 'they', 'we', 'you', 'his', 'her', 'their', 'its', 'our', 'my', 'this', 'that', 'these', 'those', 'god', 'lord', 'chapter', 'letter', 'part', 'book', 'volume', 'narrator', 'english', 'french', 'christian', 'christmas', 'sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december', 'meanwhile', 'later', 'when', 'after', 'before', 'as', 'in', 'on', 'at', 'but', 'and', 'so', 'then', 'there', 'here', 'while', 'although', 'though', 'despite', 'during', 'once', 'now', 'both', 'each', 'one', 'two', 'three']);

const STARTERS = new Set(['eager', 'still', 'soon', 'finally', 'instead', 'however', 'unaware', 'alone', 'together', 'unable', 'nothing', 'everything', 'someone', 'something', 'nobody', 'everyone', 'few', 'many', 'some', 'most', 'all', 'none', 'other', 'others', 'another', 'even', 'only', 'just', 'yet', 'thus', 'also', 'again', 'far', 'home', 'back', 'inside', 'outside', 'upon', 'from', 'with', 'without', 'by', 'for', 'to', 'of', 'if', 'what', 'who', 'why', 'how', 'where', 'which', 'whose', 'his', 'her', 'its', 'no', 'not', 'nor', 'or', 'yes', 'young', 'old', 'new', 'long', 'over', 'under', 'through', 'across', 'along', 'around', 'near', 'above', 'below', 'until', 'since', 'because', 'whether', 'having', 'being']);
const words = (s) => String(s || '').normalize('NFKC').match(/[\p{L}][\p{L}'’-]*/gu) || [];
const key = (w) => w.toLowerCase().replace(/[’']s$/, '').replace(/[’']/g, "'");

/** A lookup of every word in the source text, lowercased. */
export function vocabulary(...texts) {
  const set = new Set();
  for (const t of texts) for (const w of words(t)) { set.add(key(w)); }
  return set;
}

const isProper = (w) => /^\p{Lu}/u.test(w) && !COMMON.has(key(w)) && !HONORIFIC.test(w.replace(/\.$/, ''));

/** Capitalized words (not at the start of a sentence) that never appear in the source. */
export function unseenNames(prose, vocab, { phrase = false } = {}) {
  const out = new Set();
  for (const sentence of String(prose || '').split(/(?<=[.!?…])\s+|\n+/)) {
    const ws = words(sentence);
    ws.forEach((w, i) => { if (i > 0 && isProper(w) && !vocab.has(key(w))) out.add(key(w)); });
    // At the start of a sentence any word is capitalized, so skip ordinary openers
    // ("Excited by…", "Hoping…", "Eventually…") and words the text uses in lowercase.
    const w0 = ws[0];
    // In a short label ("Lighthouse keeper") the first word is capitalized anyway; only a possessive counts.
    if (phrase) { const raw0 = (String(sentence).trim().match(/^[\p{L}][\p{L}'’-]*/u) || [''])[0]; if (/[’']s$/.test(raw0) && isProper(raw0) && !vocab.has(key(raw0))) out.add(key(raw0)); continue; }
    if (w0 && isProper(w0) && !vocab.has(key(w0)) && w0.length >= 3 && !/(ing|ed|ly|ful|ness|less|ous|ive|able)$/i.test(w0) && !STARTERS.has(key(w0))) out.add(key(w0));
  }
  return out;
}

/** Drop sentences that mention a name the reader hasn't met. */
export function dropUnseen(prose, vocab, opts) {
  const bad = unseenNames(prose, vocab, opts);
  if (!bad.size) return String(prose || '');
  return String(prose || '').split(/(?<=[.!?…])\s+/).filter((s) => !words(s).some((w) => bad.has(key(w)))).join(' ').trim();
}

/**
 * Make a character's name agree with the text: keep it if every proper word
 * appears; otherwise keep only the words that do (with an honorific the text
 * uses, e.g. "Margaret Saville" → "Mrs. Saville"); otherwise try its other
 * names; otherwise null (the character isn't supported by the text).
 */
export function supportedName(name, aka, sourceText, vocab) {
  const tryOne = (n) => {
    const ws = words(n);
    const proper = ws.filter(isProper);
    if (!proper.length) return n; // a descriptive name ("the new tenant")
    const seen = proper.filter((w) => vocab.has(key(w)));
    if (seen.length === proper.length) return n;
    if (!seen.length) return null;
    const last = seen[seen.length - 1];
    const esc = last.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const m = new RegExp(`\\b((?:Mr|Mrs|Miss|Ms|Dr|Lady|Lord|Sir|Captain|Colonel|Uncle|Aunt)\\.?\\s+)${esc}\\b`, 'i').exec(sourceText);
    return m ? `${m[1].trim()} ${last}` : seen.join(' ');
  };
  return tryOne(name) || (aka || []).map(tryOne).find(Boolean) || null;
}
