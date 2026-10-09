// Writes import test files into the directory given as argv[2].
import { writeFileSync, openSync, ftruncateSync, closeSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import JSZip from 'jszip';
import { makeFixtureEpub, fixtureText } from '../fixtures/fixtures.mjs';

const dir = process.argv[2];
mkdirSync(dir, { recursive: true });

writeFileSync(join(dir, 'lantern.epub'), await makeFixtureEpub({ title: 'The Lantern Keeper’s Almanac (imported)' }));
writeFileSync(join(dir, 'tide-tables.txt'), fixtureText());
writeFileSync(join(dir, 'not-a-book.epub'), Buffer.from('This is plainly not a zip file at all.'));
const brokenZip = new JSZip();
brokenZip.file('hello.txt', 'a zip without an EPUB container');
writeFileSync(join(dir, 'broken.epub'), await brokenZip.generateAsync({ type: 'nodebuffer' }));
const drm = await JSZip.loadAsync(await makeFixtureEpub({ title: 'Locked' }));
drm.file('META-INF/encryption.xml', '<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>');
writeFileSync(join(dir, 'drm.epub'), await drm.generateAsync({ type: 'nodebuffer' }));
// 61 MB sparse file with a ZIP header: over the EPUB limit.
const big = join(dir, 'huge.epub');
writeFileSync(big, Buffer.from([0x50, 0x4b, 0x03, 0x04]));
const fd = openSync(big, 'r+'); ftruncateSync(fd, 61 * 1024 * 1024); closeSync(fd);
// Minimal valid one-page PDF.
const pdf = `%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 200]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj
4 0 obj<</Length 44>>stream
BT /F1 18 Tf 40 100 Td (Mavis PDF test) Tj ET
endstream endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
trailer<</Root 1 0 R>>
%%EOF`;
writeFileSync(join(dir, 'field-notes.pdf'), pdf);
writeFileSync(join(dir, 'picture.png'), Buffer.from('89504e470d0a1a0a', 'hex'));
console.log('files written to', dir);
