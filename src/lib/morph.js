// Plain-English descriptions of grammar codes in the interlinear:
//  * Hebrew/Aramaic: Open Scriptures Hebrew Bible morphology ("HVqp3ms", "HR/Ncfsa")
//  * Greek: Robinson's morphological codes ("V-AAI-3S", "N-NSM", "T-GPM")
// Unknown codes are returned as-is rather than guessed.

const GENDER = { m: 'masculine', f: 'feminine', c: 'common', b: 'both genders', n: 'neuter' };
const NUMBER = { s: 'singular', p: 'plural', d: 'dual' };
const STATE = { a: 'absolute', c: 'construct', d: 'determined' };
const PERSON = { 1: '1st person', 2: '2nd person', 3: '3rd person' };

const HEB_STEM = {
  q: 'Qal', N: 'Niphal', p: 'Piel', P: 'Pual', h: 'Hiphil', H: 'Hophal', t: 'Hithpael', o: 'Polel', O: 'Polal', r: 'Hithpolel',
  m: 'Poel', M: 'Poal', k: 'Palel', K: 'Pulal', Q: 'Qal passive', l: 'Pilpel', L: 'Polpal', f: 'Hithpalpel', D: 'Nithpael',
  j: 'Pealal', i: 'Pilel', u: 'Hothpaal', c: 'Tiphil', v: 'Hishtaphel', w: 'Nithpalel', y: 'Nithpoel', z: 'Hithpoel',
};
const ARAM_STEM = {
  q: 'Peal', Q: 'Peil', u: 'Hithpeel', p: 'Pael', P: 'Ithpaal', M: 'Hithpaal', a: 'Aphel', h: 'Haphel', s: 'Saphel', e: 'Shaphel',
  H: 'Hophal', i: 'Ithpeel', t: 'Hishtaphel', v: 'Ishtaphel', w: 'Hithaphel', o: 'Polel', z: 'Ithpoel', r: 'Hithpolel',
  f: 'Hithpalpel', b: 'Hephal', c: 'Tiphel', m: 'Poel', l: 'Palpel', L: 'Ithpalpel', O: 'Ithpolel', G: 'Ittaphal',
};
const HEB_CONJ = {
  p: 'perfect', q: 'sequential perfect', i: 'imperfect', w: 'sequential imperfect', h: 'cohortative', j: 'jussive',
  v: 'imperative', r: 'participle (active)', s: 'participle (passive)', a: 'infinitive absolute', c: 'infinitive construct',
};
const HEB_POS = {
  A: 'Adjective', C: 'Conjunction', D: 'Adverb', N: 'Noun', P: 'Pronoun', R: 'Preposition', S: 'Suffix', T: 'Particle', V: 'Verb',
};
const NOUN_TYPE = { c: '', g: 'gentilic', p: 'proper name', t: 'title' };
const ADJ_TYPE = { a: '', c: 'cardinal number', g: 'gentilic', o: 'ordinal number' };
const PRON_TYPE = { d: 'demonstrative', f: 'indefinite', i: 'interrogative', p: 'personal', r: 'relative' };
const SUFF_TYPE = { d: 'directional ה', h: 'paragogic ה', n: 'paragogic נ', p: 'pronoun' };
const PART_TYPE = { a: 'affirmation', d: 'definite article', e: 'exhortation', i: 'interrogative', j: 'interjection', m: 'demonstrative', n: 'negative', o: 'direct object marker', r: 'relative' };

function pgn(s) {
  // person, gender, number in any combination present
  const out = [];
  for (const ch of s) {
    if (PERSON[ch]) out.push(PERSON[ch]);
    else if (GENDER[ch]) out.push(GENDER[ch]);
    else if (NUMBER[ch]) out.push(NUMBER[ch]);
  }
  return out.join(' ');
}

function hebSegment(seg, aramaic) {
  const pos = seg[0];
  const rest = seg.slice(1);
  const name = HEB_POS[pos];
  if (!name) return seg;
  const bits = [name];
  switch (pos) {
    case 'V': {
      const stem = (aramaic ? ARAM_STEM : HEB_STEM)[rest[0]];
      const conj = HEB_CONJ[rest[1]];
      if (stem) bits.push(stem);
      if (conj) bits.push(conj);
      const tail = rest.slice(2);
      if (/[rs]/.test(rest[1])) bits.push([GENDER[tail[0]], NUMBER[tail[1]], STATE[tail[2]]].filter(Boolean).join(' '));
      else bits.push(pgn(tail));
      break;
    }
    case 'N': {
      if (NOUN_TYPE[rest[0]]) bits.push(NOUN_TYPE[rest[0]]);
      bits.push([GENDER[rest[1]], NUMBER[rest[2]], STATE[rest[3]]].filter(Boolean).join(' '));
      break;
    }
    case 'A': {
      if (ADJ_TYPE[rest[0]]) bits.push(ADJ_TYPE[rest[0]]);
      bits.push([GENDER[rest[1]], NUMBER[rest[2]], STATE[rest[3]]].filter(Boolean).join(' '));
      break;
    }
    case 'P': bits.push(PRON_TYPE[rest[0]] || '', pgn(rest.slice(1))); break;
    case 'S': bits.push(SUFF_TYPE[rest[0]] || '', pgn(rest.slice(1))); break;
    case 'T': bits.push(PART_TYPE[rest[0]] || ''); break;
    case 'R': if (rest[0] === 'd') bits.push('with article'); break;
    default: break;
  }
  return bits.filter(Boolean).join(' · ');
}

export function describeHebrew(code) {
  const c = String(code || '');
  if (!/^[HA]/.test(c)) return c;
  const aramaic = c[0] === 'A';
  const segs = c.slice(1).split('/').filter(Boolean);
  const parts = segs.map((s) => hebSegment(s, aramaic));
  return (aramaic ? 'Aramaic: ' : '') + parts.join(' + ');
}

// ---------- Greek (Robinson) ----------
const G_CASE = { N: 'nominative', G: 'genitive', D: 'dative', A: 'accusative', V: 'vocative' };
const G_NUM = { S: 'singular', P: 'plural' };
const G_GEN = { M: 'masculine', F: 'feminine', N: 'neuter' };
const G_TENSE = { P: 'present', I: 'imperfect', F: 'future', A: 'aorist', R: 'perfect', L: 'pluperfect', X: '' };
const G_VOICE = { A: 'active', M: 'middle', P: 'passive', E: 'middle/passive', D: 'middle (deponent)', O: 'passive (deponent)', N: 'middle/passive (deponent)', Q: 'impersonal active', X: '' };
const G_MOOD = { I: 'indicative', S: 'subjunctive', O: 'optative', M: 'imperative', N: 'infinitive', P: 'participle', R: 'participle (imperative sense)' };
const G_POS = {
  N: 'Noun', A: 'Adjective', T: 'Article', V: 'Verb', P: 'Personal pronoun', R: 'Relative pronoun', C: 'Reciprocal pronoun',
  D: 'Demonstrative pronoun', K: 'Correlative pronoun', I: 'Interrogative pronoun', X: 'Indefinite pronoun', Q: 'Correlative/interrogative pronoun',
  F: 'Reflexive pronoun', S: 'Possessive pronoun',
};
const G_WORD = {
  ADV: 'Adverb', CONJ: 'Conjunction', COND: 'Conditional particle', PRT: 'Particle', PREP: 'Preposition', INJ: 'Interjection',
  ARAM: 'Aramaic word', HEB: 'Hebrew word', 'N-PRI': 'Proper name (indeclinable)', 'A-NUI': 'Number (indeclinable)', LET: 'Letter',
};
const G_SUFFIX = { P: 'person', L: 'place', T: 'title', N: 'nation', G: 'gentilic', K: 'contracted', C: 'comparative', S: 'superlative', I: 'interrogative', NEG: 'negative', ATT: 'Attic' };

function caseNumGen(s) {
  return [G_CASE[s[0]], G_NUM[s[1]], G_GEN[s[2]]].filter(Boolean).join(' ');
}

export function describeGreek(code) {
  const c = String(code || '').toUpperCase();
  if (G_WORD[c]) return G_WORD[c];
  const parts = c.split('-');
  const head = parts[0];
  if (G_WORD[head] && parts.length > 1) return `${G_WORD[head]} · ${parts.slice(1).map((p) => G_SUFFIX[p] || p).join(' ')}`;
  if (head === 'V') {
    const tvm = (parts[1] || '').replace(/^2/, '');
    const second = (parts[1] || '').startsWith('2') ? 'second ' : '';
    const bits = ['Verb', second + (G_TENSE[tvm[0]] || ''), G_VOICE[tvm[1]] || '', G_MOOD[tvm[2]] || ''];
    const pn = parts[2] || '';
    if (/^[123][SP]$/.test(pn)) bits.push(`${PERSON[pn[0]]} ${G_NUM[pn[1]]}`);
    else if (/^[NGDAV][SP][MFN]$/.test(pn)) bits.push(caseNumGen(pn));
    return bits.filter((b) => b && b.trim()).join(' · ');
  }
  const pos = G_POS[head];
  if (!pos) return code;
  const rest = parts.slice(1);
  const bits = [pos];
  for (const p of rest) {
    if (/^[NGDAV][SP][MFN]?$/.test(p)) bits.push(caseNumGen(p));
    else if (/^[123][NGDAV][SP]$/.test(p)) bits.push(`${PERSON[p[0]]} ${G_CASE[p[1]]} ${G_NUM[p[2]]}`);
    else if (G_SUFFIX[p]) bits.push(G_SUFFIX[p]);
  }
  return bits.join(' · ');
}

export const describe = (code, lang) => (lang === 'grc' ? describeGreek(code) : describeHebrew(code));
