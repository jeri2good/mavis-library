// Self-hosted fonts (bundled, so they work offline and inside the reader).
import literata from '@fontsource-variable/literata/files/literata-latin-wght-normal.woff2?url';
import literataI from '@fontsource-variable/literata/files/literata-latin-wght-italic.woff2?url';
import sourceSerif from '@fontsource-variable/source-serif-4/files/source-serif-4-latin-wght-normal.woff2?url';
import sourceSerifI from '@fontsource-variable/source-serif-4/files/source-serif-4-latin-wght-italic.woff2?url';
import atkinson from '@fontsource-variable/atkinson-hyperlegible-next/files/atkinson-hyperlegible-next-latin-wght-normal.woff2?url';
import atkinsonI from '@fontsource-variable/atkinson-hyperlegible-next/files/atkinson-hyperlegible-next-latin-wght-italic.woff2?url';
import fraunces from '@fontsource-variable/fraunces/files/fraunces-latin-soft-normal.woff2?url';

const abs = (u) => new URL(u, location.href).href;

const faces = [
  ['Mavis Literata', literata, 'normal', '200 900'],
  ['Mavis Literata', literataI, 'italic', '200 900'],
  ['Mavis Source Serif', sourceSerif, 'normal', '200 900'],
  ['Mavis Source Serif', sourceSerifI, 'italic', '200 900'],
  ['Mavis Atkinson', atkinson, 'normal', '200 800'],
  ['Mavis Atkinson', atkinsonI, 'italic', '200 800'],
  ['Mavis Fraunces', fraunces, 'normal', '100 900'],
];

export function fontFaceCSS() {
  return faces.map(([family, url, style, weight]) =>
    `@font-face{font-family:'${family}';src:url('${abs(url)}') format('woff2');font-style:${style};font-weight:${weight};font-display:swap}`).join('\n');
}

export function installAppFonts() {
  const s = document.createElement('style');
  s.id = 'mavis-fonts';
  s.textContent = fontFaceCSS();
  document.head.appendChild(s);
}

/** Reading typefaces offered in the reader (all bundled except "Publisher"). */
export const READER_FONTS = {
  literata: { label: 'Literata', stack: "'Mavis Literata', Georgia, serif" },
  sourceSerif: { label: 'Source Serif', stack: "'Mavis Source Serif', 'Iowan Old Style', Georgia, serif" },
  atkinson: { label: 'Atkinson (high legibility)', stack: "'Mavis Atkinson', system-ui, sans-serif" },
  system: { label: 'System sans', stack: "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif" },
  publisher: { label: 'Publisher default', stack: null },
};
