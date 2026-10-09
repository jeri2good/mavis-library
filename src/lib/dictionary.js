// Definitions from the Free Dictionary API (dictionaryapi.dev), which serves
// Wiktionary-derived data. English only. Only the looked-up word is sent.

const cache = new Map();

export function normalizeTerm(s) {
  return String(s || '')
    .replace(/[‘’]/g, "'")
    .replace(/^[^\p{L}]+|[^\p{L}']+$/gu, '')
    .trim()
    .toLowerCase()
    .slice(0, 60);
}

export async function define(term, { signal } = {}) {
  const word = normalizeTerm(term);
  if (!word) return { word, entries: [], error: 'Select a single word to look it up.' };
  if (word.split(/\s+/).length > 3) return { word, entries: [], error: 'Select a single word or short phrase to look it up.' };
  if (cache.has(word)) return cache.get(word);
  if (!navigator.onLine) return { word, entries: [], error: "You're offline. Definitions need an internet connection." };
  let res;
  try {
    res = await fetch(`https://api.dictionaryapi.dev/api/v2/entries/en/${encodeURIComponent(word)}`, { signal, referrerPolicy: 'no-referrer' });
  } catch (err) {
    if (signal?.aborted) throw err;
    return { word, entries: [], error: 'The dictionary could not be reached. Try again in a moment.', retry: true };
  }
  if (res.status === 404) {
    const out = { word, entries: [], error: `No definition found for “${word}”.` };
    cache.set(word, out);
    return out;
  }
  if (!res.ok) return { word, entries: [], error: `The dictionary answered ${res.status}. Try again later.`, retry: true };
  const data = await res.json();
  const entries = (Array.isArray(data) ? data : []).slice(0, 3).map((e) => ({
    word: e.word,
    phonetic: e.phonetic || e.phonetics?.find((p) => p.text)?.text || '',
    meanings: (e.meanings || []).slice(0, 4).map((m) => ({
      partOfSpeech: m.partOfSpeech,
      definitions: (m.definitions || []).slice(0, 3).map((d) => ({ definition: d.definition, example: d.example || '' })),
      synonyms: (m.synonyms || []).slice(0, 5),
    })),
  }));
  const out = { word, entries, source: 'Free Dictionary API · Wiktionary' };
  cache.set(word, out);
  return out;
}
