"""
Mavis Library end-to-end tests (Playwright + Chromium).

  npm run build
  python3 tests/e2e/run_e2e.py

Writes docs/test-evidence/e2e-report.md and screenshots. External services are
replaced by fixtures (see tests/e2e/server.mjs); speech engines are simulated
where noted, because headless Chromium on Linux has no voices or microphone.
"""
import json, os, re, subprocess, sys, time, traceback, tempfile, pathlib
from playwright.sync_api import sync_playwright, expect

ROOT = pathlib.Path(__file__).resolve().parents[2]
EVID = ROOT / 'docs' / 'test-evidence'
SHOTS = EVID / 'screenshots'
SHOTS.mkdir(parents=True, exist_ok=True)
for _old in SHOTS.glob('fail-*.png'):
    _old.unlink()
FILES = pathlib.Path(tempfile.mkdtemp(prefix='mavis-files-'))
BASE = 'http://localhost:4321'
AUTH = 'http://localhost:4322'
expect.set_options(timeout=8000)

results = []
console_problems = []

def record(name, ok, detail=''):
    results.append({'name': name, 'ok': ok, 'detail': detail})
    print(('  PASS ' if ok else '  FAIL ') + name + (f' — {detail}' if detail and not ok else ''))

def test(name):
    def deco(fn):
        def run(*a, **k):
            try:
                fn(*a, **k)
                record(name, True)
            except Exception as e:
                record(name, False, f'{type(e).__name__}: {(str(e).splitlines() or [""])[0][:300]}')
                traceback.print_exc(limit=2)
                try:
                    for arg in a:
                        if hasattr(arg, 'screenshot'):
                            arg.screenshot(path=str(SHOTS / ('fail-' + re.sub(r'\W+', '-', name)[:60] + '.png')))
                            break
                except Exception:
                    pass
        run.__name__ = fn.__name__
        return run
    return deco

FAKE_TTS = """
(() => {
  window.__utter = []; window.__cancels = 0; window.__ttsDelay = 40;
  class FakeUtt { constructor(t) { this.text = t; this.rate = 1; } }
  const voices = [{ name: 'Test Voice', lang: 'en-US', voiceURI: 'test-voice', localService: true, default: true },
                  { name: 'Cloud Voice', lang: 'en-GB', voiceURI: 'cloud-voice', localService: false, default: false }];
  const synth = {
    getVoices() { return voices; },
    speak(u) { window.__utter.push(u.text); this.current = u; const my = u;
      this._t = setTimeout(() => { if (this.current === my) { this.current = null; my.onend && my.onend({}); } }, window.__ttsDelay); },
    cancel() { clearTimeout(this._t); const c = this.current; this.current = null; window.__cancels++; if (c && c.onerror) c.onerror({ error: 'interrupted' }); },
    pause() {}, resume() {}, addEventListener() {}, removeEventListener() {},
  };
  Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true });
  window.SpeechSynthesisUtterance = FakeUtt;
})();
"""

def fake_recognition(mode):
    return """
(() => {
  const MODE = '%s';
  if (MODE === 'none') { delete window.SpeechRecognition; delete window.webkitSpeechRecognition;
    Object.defineProperty(window, 'webkitSpeechRecognition', { value: undefined, configurable: true }); return; }
  class FakeRec {
    start() { window.__micStarted = (window.__micStarted || 0) + 1;
      setTimeout(() => this.onstart && this.onstart(), 30);
      if (MODE === 'deny') setTimeout(() => { this.onerror && this.onerror({ error: 'not-allowed' }); this.onend && this.onend(); }, 80);
      if (MODE === 'ok') setTimeout(() => { const r = [{ transcript: 'pride' }]; r.isFinal = true;
        this.onresult && this.onresult({ resultIndex: 0, results: [r] }); this.onend && this.onend(); }, 120); }
    abort() { window.__micAborted = true; } stop() {}
  }
  window.SpeechRecognition = FakeRec; window.webkitSpeechRecognition = FakeRec;
})();
""" % mode

DICT = json.dumps([{'word': 'harbor', 'phonetic': '/ˈhɑːbə/', 'meanings': [{'partOfSpeech': 'noun', 'definitions': [{'definition': 'A sheltered expanse of water where ships can anchor.', 'example': 'The boats came home to the harbor.'}], 'synonyms': ['port', 'haven']}]}])
PNG = bytes.fromhex('89504e470d0a1a0a0000000d49484452000000010000000108020000009077' '53de0000000c4944415478da63f8cfc0f01f0005fe02fea7c66bb40000000049454e44ae426082')

def _cover_png():
    import io
    from PIL import Image, ImageDraw
    im = Image.new('RGB', (120, 180), (120, 40, 50)); d = ImageDraw.Draw(im); d.rectangle([10, 10, 110, 60], fill=(230, 210, 150))
    b = io.BytesIO(); im.save(b, 'PNG'); return b.getvalue()
COVER_PNG = _cover_png()

def new_context(browser, *, tts=True, mic='ok', **kw):
    ctx = browser.new_context(viewport=kw.pop('viewport', {'width': 1280, 'height': 860}), **kw)
    if tts: ctx.add_init_script(FAKE_TTS)
    if mic: ctx.add_init_script(fake_recognition(mic))
    ctx.route('https://api.dictionaryapi.dev/**', lambda r: r.fulfill(status=200, content_type='application/json', body=DICT, headers={'access-control-allow-origin': '*'}))
    ctx.route('https://www.gutenberg.org/**', lambda r: r.fulfill(status=404, body=''))
    ctx.route('https://covers.openlibrary.org/**', lambda r: r.fulfill(status=200, content_type='image/png', body=PNG))
    ctx.route('https://books.google.com/**', lambda r: r.fulfill(status=200, content_type='image/png', body=COVER_PNG))
    return ctx

def watch(page, label):
    def on_console(m):
        t = m.text
        if 'Blocked script execution' in t:  # the hostile fixture's script being blocked by the sandbox: expected
            return
        if 'setting end offset to start container length failed' in t:  # epub.js logs and recovers while measuring page breaks
            return
        if m.type == 'error' and 'Failed to load resource' not in t:
            console_problems.append(f'[{label}] console error: {t[:300]}')
        if 'Content Security Policy' in t or 'Refused to' in t:
            console_problems.append(f'[{label}] CSP: {t[:300]}')
    page.on('console', on_console)
    page.on('pageerror', lambda e: console_problems.append(f'[{label}] page error: {e}'))
    return page

def wait_until(page, js, timeout=10000):
    end = time.time() + timeout / 1000
    while time.time() < end:
        if page.evaluate(js):
            return
        page.wait_for_timeout(100)
    raise AssertionError(f'timed out waiting for: {js}')

def frame(page):
    return page.frame_locator('#viewer iframe').first

def wait_reader(page):
    expect(page.locator('#r-loading')).to_be_hidden(timeout=15000)
    expect(frame(page).locator('body')).to_contain_text(re.compile(r'\w'), timeout=10000)
    page.wait_for_timeout(400)

def chapter(page):
    return page.locator('#r-chapter').inner_text().strip()

def no_hscroll(page):
    return page.evaluate('document.scrollingElement.scrollWidth <= window.innerWidth + 1')

def shot(page, name, full=False):
    page.screenshot(path=str(SHOTS / f'{name}.png'), full_page=full)

def download_first_classic(page):
    page.goto(BASE + '/#/book/gutenberg:1342')
    page.get_by_role('button', name=re.compile('Download')).click()
    page.wait_for_url(re.compile(r'#/read/'), timeout=15000)
    wait_reader(page)

# ======================================================================
def run_public(browser):
    ctx = new_context(browser)
    page = watch(ctx.new_page(), 'public')

    @test('Home loads anonymously with live catalog rows and cover fallbacks')
    def _(page):
        page.goto(BASE + '/')
        expect(page.get_by_role('heading', name=re.compile('Your books'))).to_be_visible()
        expect(page.locator('#classics .book-card').first).to_be_visible()
        assert page.locator('#classics .book-card').count() == 16
        expect(page.locator('#classics .book-card').first).to_contain_text('Pride and Prejudice')
        # Gutenberg covers 404 in the test → typographic fallback shown, broken <img> removed.
        # Pride and Prejudice gets a real published cover (Google Books fixture);
        # the rest fall back to designed covers when no real cover is found.
        expect(page.locator('#classics .book-card').first.locator('.cover')).to_have_class(re.compile('has-real-cover'), timeout=8000)
        assert page.locator('#classics .cover-gen').count() == 16
        assert page.locator('#classics .cover.has-real-cover').count() == 1
        assert no_hscroll(page), 'page scrolls horizontally'
        shot(page, '01-home-desktop')
    _(page)

    @test('Search by title/author, pagination, and language filter')
    def _(page):
        page.goto(BASE + '/#/search?q=austen')
        expect(page.locator('.result-count')).to_contain_text('Page 1')
        n1 = page.locator('#results .book-card').count()
        assert n1 == 25, n1
        page.get_by_role('link', name=re.compile('Next')).click()
        expect(page).to_have_url(re.compile('page=2'))
        expect(page.locator('.result-count')).to_contain_text('Page 2')
        page.select_option('#lang', 'fr')
        expect(page).to_have_url(re.compile('lang=fr'))
        expect(page.locator('#results .book-card').first).to_contain_text(re.compile('.'))
        expect(page.locator('#results .badge').filter(has_text='French').first).to_be_visible()
        shot(page, '02-search')
    _(page)

    @test('Search failure shows an error with a working retry; empty results explain next steps')
    def _(page):
        page.goto(BASE + '/#/search?q=failonce')
        expect(page.get_by_role('alert')).to_contain_text('answered 503')
        page.get_by_role('button', name=re.compile('Try again')).click()
        expect(page.locator('#results .book-card').first).to_be_visible()
        page.goto(BASE + '/#/search?q=zzzz')
        expect(page.locator('#results')).to_contain_text('No books found')
        expect(page.get_by_role('link', name='Search all books')).to_be_visible()
    _(page)

    @test('Open Library discovery with honest borrow and buy links')
    def _(page):
        page.goto(BASE + '/#/search?q=dune&src=all')
        expect(page.locator('#results .book-card').first).to_contain_text('Dune')
        page.locator('#results .book-card').first.click()
        expect(page.get_by_role('heading', level=1)).to_have_text('Dune')
        expect(page.locator('.summary')).to_contain_text('desert planet')
        hrefs = page.eval_on_selector_all('.provider a', 'els => els.map(e => e.href)')
        assert any(h.startswith('https://www.overdrive.com/search?q=Dune') for h in hrefs), hrefs
        assert any('hoopladigital.com/search?q=Dune' in h for h in hrefs), hrefs
        assert any('kobo.com/us/en/search?query=9780441172719' in h for h in hrefs), hrefs
        assert any('play.google.com/store/search?q=9780441172719&c=books' in h for h in hrefs), hrefs
        assert page.eval_on_selector_all('.provider a', 'els => els.every(e => e.target === "_blank" && e.rel.includes("noopener"))'), 'external links must open safely'
        expect(page.locator('#borrow-h').locator('..')).to_contain_text("doesn't connect to your library card")
        shot(page, '03-openlibrary-detail', full=True)
    _(page)

    @test('Free book details, real download through /api/epub, and reader opens')
    def _(page):
        page.goto(BASE + '/#/book/gutenberg:1342')
        expect(page.locator('.badge').filter(has_text='Public domain')).to_be_visible()
        expect(page.locator('.notice')).to_contain_text("check your country")
        shot(page, '04-book-detail', full=True)
        page.get_by_role('button', name=re.compile('Download')).click()
        page.wait_for_url(re.compile(r'#/read/gutenberg'), timeout=15000)
        wait_reader(page)
        expect(frame(page).locator('h1').first).to_have_text('The Ninety-One Steps')
        shot(page, '05-reader')
    _(page)

    @test('EPUB content is sandboxed: scripts and inline handlers never run; external links ask first')
    def _(page):
        sandbox = page.locator('#viewer iframe').first.get_attribute('sandbox')
        assert 'allow-scripts' not in (sandbox or ''), sandbox
        assert page.evaluate('window.__mavisHostile') is None
        f = frame(page)
        assert f.locator('script').count() == 0
        f.locator('#hostile-click').click()
        assert page.evaluate('window.__mavisHostileClick') is None
        url_before = page.url
        f.locator('#external-link').click()
        expect(page.get_by_role('dialog', name='Leave the book?')).to_be_visible()
        expect(page.get_by_role('dialog', name='Leave the book?')).to_contain_text('example.com')
        assert page.url == url_before and len(ctx.pages) == 1
        page.get_by_role('button', name='Stay here').click()
    _(page)

    @test('Table of contents, page turning (keys and buttons), and saved position after reload')
    def _(page):
        page.locator('[data-act="toc"]').click()
        dlg = page.get_by_role('dialog', name='Contents')
        expect(dlg.locator('.toc-list button')).to_have_count(4)
        dlg.get_by_role('button', name='Notes in the Margins').click()
        expect(page.locator('#r-chapter')).to_have_text('Notes in the Margins')
        p1 = page.locator('#r-page').inner_text()
        page.keyboard.press('ArrowRight')
        expect(page.locator('#r-page')).not_to_have_text(p1)
        p2 = page.locator('#r-page').inner_text()
        page.locator('.reader-bar.bottom [data-act="prev"]').click()
        expect(page.locator('#r-page')).to_have_text(p1)
        page.locator('.reader-bar.bottom [data-act="next"]').click()
        expect(page.locator('#r-page')).to_have_text(p2)
        page.wait_for_timeout(1200)  # progress save is debounced
        page.reload()
        wait_reader(page)
        expect(page.locator('#r-chapter')).to_have_text('Notes in the Margins')
        expect(page.locator('#r-page')).to_have_text(p2)
        expect(page.locator('#r-pct')).to_have_text(re.compile(r'\d+%'), timeout=15000)
    _(page)

    @test('Bookmarks: add, list, jump back, remove')
    def _(page):
        here = page.locator('#r-page').inner_text()
        page.locator('[data-act="bookmark"]').click()
        expect(page.locator('[data-act="bookmark"]')).to_have_attribute('aria-pressed', 'true')
        page.locator('[data-act="toc"]').click()
        dlg = page.get_by_role('dialog', name='Contents')
        dlg.get_by_role('button', name='The Ninety-One Steps').click()
        expect(page.locator('#r-chapter')).to_have_text('The Ninety-One Steps')
        expect(page.locator('[data-act="bookmark"]')).to_have_attribute('aria-pressed', 'false')
        page.locator('[data-act="toc"]').click()
        dlg = page.get_by_role('dialog', name='Contents')
        dlg.get_by_role('tab', name=re.compile('Bookmarks')).click()
        expect(dlg.locator('[data-pane="marks"] .ann-item')).to_have_count(1)
        dlg.locator('[data-pane="marks"] [data-go]').click()
        expect(page.locator('#r-page')).to_have_text(here)
        expect(page.locator('[data-act="bookmark"]')).to_have_attribute('aria-pressed', 'true')
    _(page)

    def select_text(page, para_index=2, start=4, end=40):
        frame(page).locator('body').evaluate(f"""b => {{
            const p = b.ownerDocument.querySelectorAll('p')[{para_index}];
            const t = p.firstChild; const r = b.ownerDocument.createRange();
            r.setStart(t, {start}); r.setEnd(t, {end});
            const s = b.ownerDocument.getSelection(); s.removeAllRanges(); s.addRange(r);
        }}""")
        expect(page.locator('#sel')).to_be_visible(timeout=4000)

    @test('Highlights and notes: create from a selection, persist across reload, list and delete')
    def _(page):
        page.locator('[data-act="toc"]').click()
        page.get_by_role('dialog', name='Contents').get_by_role('button', name='Fog Over the Harbor').click()
        expect(page.locator('#r-chapter')).to_have_text('Fog Over the Harbor')
        page.wait_for_timeout(300)
        select_text(page, 1, 4, 30)
        shot(page, '06-selection-toolbar')
        page.get_by_role('button', name='Highlight mint').click()
        expect(page.locator('.toast').filter(has_text='Highlighted')).to_be_visible()
        expect(page.locator('#viewer svg g.hl-mint')).to_have_count(1)
        page.wait_for_timeout(300)
        select_text(page, 2, 0, 25)
        page.get_by_role('button', name='Add a note').click()
        dlg = page.get_by_role('dialog', name='Note')
        dlg.locator('#note-text').fill('Remember the fog bell.')
        dlg.get_by_role('button', name='Save').click()
        expect(page.locator('.toast').filter(has_text='Note saved')).to_be_visible()
        page.wait_for_timeout(1200)
        page.reload(); wait_reader(page)
        expect(page.locator('#viewer svg g[class^="hl-"]')).to_have_count(2, timeout=8000)
        page.locator('[data-act="toc"]').click()
        dlg = page.get_by_role('dialog', name='Contents')
        dlg.get_by_role('tab', name=re.compile('Notes')).click()
        expect(dlg.locator('[data-pane="notes"] .ann-item')).to_have_count(2)
        expect(dlg.locator('[data-pane="notes"]')).to_contain_text('Remember the fog bell.')
        shot(page, '07-notes-panel')
        dlg.locator('[data-pane="notes"] [data-del]').first.click()
        page.get_by_role('dialog', name=re.compile('Delete this')).get_by_role('button', name='Delete').click()
        expect(dlg.locator('[data-pane="notes"] .ann-item')).to_have_count(1)
        page.keyboard.press('Escape')
    _(page)

    @test('Dictionary lookup from a selection and by typing')
    def _(page):
        page.keyboard.press('Escape')
        select_text(page, 3, 0, 9)
        page.get_by_role('button', name='Look up in dictionary').click()
        dlg = page.get_by_role('dialog', name='Dictionary')
        expect(dlg).to_contain_text('sheltered expanse of water')
        expect(dlg).to_contain_text('Free Dictionary API')
        dlg.locator('#dict-q').fill('harbor')
        dlg.locator('#dict-q').press('Enter')
        expect(dlg.locator('.dict-word')).to_have_text('harbor')
        shot(page, '08-dictionary')
        page.keyboard.press('Escape')
    _(page)

    @test('Display settings: night theme, larger text, font, margins apply live')
    def _(page):
        page.locator('[data-act="display"]').click()
        dlg = page.get_by_role('dialog', name='Display')
        dlg.get_by_role('button', name=re.compile('Night')).click()
        expect(page.locator('.reader')).to_have_attribute('data-rtheme', 'dark')
        dlg.get_by_role('button', name='Larger text').click()
        expect(dlg.locator('#size-o')).to_have_text('110%')
        dlg.locator('#r-font').select_option('atkinson')
        dlg.get_by_role('button', name='Wide').click()
        page.wait_for_timeout(500)
        color = frame(page).locator('body').evaluate('b => getComputedStyle(b).color')
        fam = frame(page).locator('p').first.evaluate('p => getComputedStyle(p).fontFamily')
        size = frame(page).locator('body').evaluate('b => getComputedStyle(b).fontSize')
        assert color == 'rgb(217, 211, 196)', color
        assert 'Mavis Atkinson' in fam, fam
        shot(page, '09-reader-night')
        dlg.get_by_role('button', name=re.compile('Paper')).click()
        dlg.get_by_role('button', name='Normal').first.click()
        page.keyboard.press('Escape')
    _(page)

    @test('Immersive focus mode hides controls and exits with Escape or the visible button')
    def _(page):
        page.locator('.reader-bar [data-act="immersive"]').click()
        expect(page.locator('.reader')).to_have_class(re.compile('immersive'))
        expect(page.locator('.exit-immersive')).to_be_visible()
        shot(page, '10-immersive')
        page.keyboard.press('Escape')
        expect(page.locator('.reader')).not_to_have_class(re.compile('immersive'))
        page.locator('.reader-bar [data-act="immersive"]').click()
        page.locator('.exit-immersive').click()
        expect(page.locator('.reader')).not_to_have_class(re.compile('immersive'))
    _(page)

    @test('Page-turn animation runs and follows navigation')
    def _(page):
        page.evaluate("""() => { window.__anim = []; const v = document.querySelector('#viewer');
            new MutationObserver(() => window.__anim.push(v.className)).observe(v, { attributes: true }); }""")
        p = page.locator('#r-page').inner_text()
        page.keyboard.press('ArrowRight')
        expect(page.locator('#r-page')).not_to_have_text(p)
        classes = ' '.join(page.evaluate('window.__anim'))
        assert 'anim-out-next' in classes and 'anim-in-next' in classes, classes
    _(page)

    @test('Read-aloud (simulated speech engine): reads the visible page, turns pages, pause/resume/stop')
    def _(page):
        page.locator('[data-act="toc"]').click()
        page.get_by_role('dialog', name='Contents').get_by_role('button', name='The Morning Watch').click()
        expect(page.locator('#r-chapter')).to_have_text('The Morning Watch')
        expect(page.locator('#r-page')).to_contain_text(re.compile(r'pages? 1\b'))
        page.wait_for_timeout(300)
        page.locator('[data-act="tts"]').click()
        expect(page.locator('#tts')).to_be_visible()
        expect(page.locator('#tts-voice option')).to_have_count(2)
        expect(page.locator('#tts')).to_contain_text('online')
        start_page = page.locator('#r-page').inner_text()
        page.evaluate('window.__utter = []; window.__ttsDelay = 30')
        page.get_by_role('button', name='Start reading aloud').click()
        expect(page.locator('#r-page')).not_to_have_text(start_page, timeout=10000)
        spoken = page.evaluate('window.__utter')
        assert spoken[0] == 'The Morning Watch', spoken[:3]
        first_page_text = spoken[:]
        assert not any('quiet close' in s for s in first_page_text), 'read text beyond the visible page'
        shot(page, '11-read-aloud')
        page.get_by_role('button', name='Pause reading aloud').click()
        n = page.evaluate('window.__utter.length')
        last = page.evaluate('window.__utter[window.__utter.length-1]')
        page.wait_for_timeout(500)
        assert page.evaluate('window.__utter.length') == n, 'kept speaking while paused'
        expect(page.locator('#tts-status')).to_contain_text('Paused')
        page.get_by_role('button', name='Resume reading aloud').click()
        page.wait_for_timeout(120)
        after = page.evaluate('window.__utter')
        assert len(after) > n and after[n] == last, 'resume should repeat the current sentence'
        # Let it reach the end of the chapter: the end marker is read exactly once.
        wait_until(page, "window.__utter.some(s => s.includes('quiet close'))", timeout=20000)
        spoken = page.evaluate('window.__utter')
        assert sum('quiet close' in s for s in spoken) == 1, 'chapter end read more than once'
        expect(page.locator('#tts-line')).to_have_text('Reached the end of the book.', timeout=10000)
        assert len(set(spoken)) / len(spoken) > 0.3, f'too many repeats: {len(set(spoken))} unique of {len(spoken)}: {spoken[-8:]}'
    _(page)

    @test('Read-aloud restarts from the new page after manual navigation, and stops when the book closes')
    def _(page):
        page.locator('[data-act="toc"]').click()
        page.get_by_role('dialog', name='Contents').get_by_role('button', name='The Ninety-One Steps').click()
        page.evaluate('window.__utter = []; window.__ttsDelay = 400')
        page.get_by_role('button', name='Start reading aloud').click()
        wait_until(page, 'window.__utter.length >= 2')
        cancels = page.evaluate('window.__cancels')
        p = page.locator('#r-page').inner_text()
        page.keyboard.press('ArrowRight')
        expect(page.locator('#r-page')).not_to_have_text(p)
        wait_until(page, f'window.__cancels > {cancels}')
        n = page.evaluate('window.__utter.length')
        wait_until(page, f'window.__utter.length > {n}', timeout=4000)
        expect(page.locator('#tts-status')).to_contain_text('Sentence')
        page.locator('[data-act="close"]').first.click()
        page.wait_for_url(re.compile(r'#/(book|shelf|$)'), timeout=5000)
        c2 = page.evaluate('window.__cancels'); n2 = page.evaluate('window.__utter.length')
        page.wait_for_timeout(1200)
        assert page.evaluate('window.__utter.length') == n2, 'kept reading after the book closed'
    _(page)

    @test('Imports: EPUB, TXT (converted), PDF; rejects malformed, DRM, oversized, and unsupported files')
    def _(page):
        page.goto(BASE + '/#/shelf')
        expect(page.get_by_role('heading', name='My shelf')).to_be_visible()
        inp = page.locator('#import-input')
        inp.set_input_files(str(FILES / 'lantern.epub'))
        expect(page.locator('.toast').filter(has_text='Imported')).to_be_visible()
        inp.set_input_files(str(FILES / 'tide-tables.txt'))
        expect(page.locator('.toast').filter(has_text='converted from plain text')).to_be_visible()
        inp.set_input_files(str(FILES / 'field-notes.pdf'))
        expect(page.locator('.toast').filter(has_text='Imported “field-notes”')).to_be_visible()
        for f, msg in [('not-a-book.epub', "isn't a ZIP"), ('broken.epub', 'missing its EPUB container'), ('drm.epub', 'DRM-protected'), ('huge.epub', 'limited to 60 MB'), ('picture.png', "isn't a supported book file")]:
            inp.set_input_files(str(FILES / f))
            expect(page.locator('.toast-error').filter(has_text=msg)).to_be_visible()
        expect(page.locator('.shelf-card')).to_have_count(4)
        shot(page, '12-shelf')
        page.locator('.shelf-card').filter(has_text='THE TIDE TABLES').locator('.book-card').click()
        wait_reader(page)
        page.locator('[data-act="toc"]').click()
        expect(page.get_by_role('dialog', name='Contents').locator('.toc-list button')).to_have_count(3)
        page.keyboard.press('Escape')
        page.locator('[data-act="close"]').first.click()
        page.goto(BASE + '/#/shelf')
        page.locator('.shelf-card').filter(has_text='field-notes').locator('.book-card').click()
        expect(page.locator('.pdf-frame')).to_be_visible()
        expect(page.locator('.reader')).to_contain_text("turned off here rather than imitated")
    _(page)

    @test('Shelf: collections, filter, sort, safe removal with confirmation and undo')
    def _(page):
        page.goto(BASE + '/#/shelf')
        expect(page.locator('[data-tab="all"]')).to_contain_text('4')
        n = int(re.search(r'(\d+)', page.locator('[data-tab="finished"]').inner_text()).group(1))
        page.locator('[data-tab="finished"]').click()
        expect(page.locator('.shelf-card')).to_have_count(n)
        page.locator('[data-tab="all"]').click()
        page.fill('#shelf-q', 'zzz')
        expect(page.locator('#shelf-body')).to_contain_text('No books match')
        page.fill('#shelf-q', 'tide')
        expect(page.locator('.shelf-card')).to_have_count(1)
        page.fill('#shelf-q', '')
        page.select_option('#shelf-filter', 'imported')
        expect(page.locator('.shelf-card')).to_have_count(3)
        page.select_option('#shelf-filter', 'any')
        page.select_option('#shelf-sort', 'title')
        card = page.locator('.shelf-card').filter(has_text='Pride and Prejudice')
        card.locator('[data-menu]').click()
        page.get_by_role('button', name='Remove from shelf').click()
        page.get_by_role('dialog', name='Remove from your shelf?').get_by_role('button', name='Cancel').click()
        expect(page.locator('.shelf-card')).to_have_count(4)
        card.locator('[data-menu]').click()
        page.get_by_role('button', name='Remove from shelf').click()
        page.get_by_role('dialog', name='Remove from your shelf?').get_by_role('button', name='Remove').click()
        expect(page.locator('.shelf-card')).to_have_count(3)
        page.locator('.toast').filter(has_text='Removed').get_by_role('button', name='Undo').click()
        expect(page.locator('.shelf-card')).to_have_count(4)
    _(page)

    @test('Keyboard only: reach search, submit, and dialogs trap and return focus')
    def _(page):
        page.goto(BASE + '/')
        page.wait_for_timeout(300)
        page.evaluate('document.activeElement && document.activeElement.blur()')
        for _ in range(20):
            page.keyboard.press('Tab')
            if page.evaluate("document.activeElement && document.activeElement.id") == 'home-q':
                break
        assert page.evaluate("document.activeElement.id") == 'home-q', 'search not reachable by Tab'
        page.keyboard.type('austen'); page.keyboard.press('Enter')
        expect(page).to_have_url(re.compile(r'#/search\?q=austen'))
        page.goto(BASE + '/#/shelf')
        btn = page.locator('[data-menu]').first
        btn.focus(); page.keyboard.press('Enter')
        dlg = page.get_by_role('dialog')
        expect(dlg).to_be_visible()
        for _ in range(12): page.keyboard.press('Tab')
        assert page.evaluate("!!document.activeElement.closest('[role=dialog]')"), 'focus escaped the dialog'
        page.keyboard.press('Escape')
        expect(dlg).to_be_hidden()
        assert page.evaluate("document.activeElement.hasAttribute('data-menu')"), 'focus not returned'
        # Visible focus indicator exists.
        outline = page.evaluate("getComputedStyle(document.activeElement).outlineStyle")
        assert outline != 'none', outline
    _(page)

    @test('Accounts not configured: account page says so plainly; guest mode keeps working')
    def _(page):
        page.goto(BASE + '/#/account')
        expect(page.locator('.account')).to_contain_text('Accounts aren’t switched on')
        expect(page.locator('.account input[type=password]')).to_have_count(0)
    _(page)

    ctx.close()

    # ---- Voice search variants (simulated recognition engines) ----
    for mode in ['ok', 'deny', 'none']:
        c = new_context(browser, mic=mode)
        p = watch(c.new_page(), f'mic-{mode}')
        @test(f'Voice search ({ {"ok": "recognized speech", "deny": "permission denied", "none": "unsupported browser"}[mode] })')
        def _(p):
            p.goto(BASE + '/')
            expect(p.locator('#classics .book-card').first).to_be_visible()
            assert p.evaluate('window.__micStarted || 0') == 0, 'microphone started before a tap'
            p.get_by_role('button', name='Search by voice').click()
            if mode == 'ok':
                dlg = p.get_by_role('dialog', name='Search books by voice')
                expect(dlg).to_contain_text('“pride”')
                expect(dlg).to_contain_text('servers')
                shot(p, '13-voice-search')
                dlg.get_by_role('button', name='Use this').click()
                expect(p).to_have_url(re.compile(r'q=pride.*voice=1'))
                expect(p.locator('.search-head')).to_contain_text('Heard “pride”')
            elif mode == 'deny':
                dlg = p.get_by_role('dialog', name='Search books by voice')
                expect(dlg).to_contain_text('Microphone access is blocked')
                dlg.get_by_role('button', name='Cancel').click()
                expect(p.locator('#home-q')).to_be_focused()
            else:
                dlg = p.get_by_role('dialog', name='Voice input isn’t available')
                expect(dlg).to_be_visible()
                dlg.get_by_role('button', name='Type instead').click()
                expect(p.locator('#home-q')).to_be_focused()
        _(p)
        c.close()

    # ---- Reduced motion ----
    c = new_context(browser, reduced_motion='reduce')
    p = watch(c.new_page(), 'reduced-motion')
    @test('Reduced motion: pages turn without animation')
    def _(p):
        download_first_classic(p)
        p.evaluate("""() => { window.__anim = []; const v = document.querySelector('#viewer');
            new MutationObserver(() => window.__anim.push(v.className)).observe(v, { attributes: true }); }""")
        pg = p.locator('#r-page').inner_text()
        p.keyboard.press('ArrowRight')
        expect(p.locator('#r-page')).not_to_have_text(pg)
        assert not any('anim' in x or 'fade' in x for x in p.evaluate('window.__anim')), p.evaluate('window.__anim')
    _(p)
    c.close()

    # ---- Mobile layout ----
    c = new_context(browser, viewport={'width': 360, 'height': 760}, is_mobile=True, has_touch=True, device_scale_factor=2)
    p = watch(c.new_page(), 'mobile')
    @test('Phone layout at 360px: no sideways scrolling; reader works with swipes and tap zones')
    def _(p):
        for route, name in [('/', '14-phone-home'), ('/#/search?q=austen', '15-phone-search'), ('/#/book/gutenberg:84', '16-phone-detail'), ('/#/shelf', None), ('/#/account', None), ('/#/settings', None)]:
            p.goto(BASE + route)
            p.wait_for_timeout(900)
            assert no_hscroll(p), f'horizontal scroll on {route}'
            if name: shot(p, name)
        expect(p.locator('.tabbar')).to_be_visible()
        tap = p.evaluate("Math.min(...[...document.querySelectorAll('.tab')].map(t => t.getBoundingClientRect().height))")
        assert tap >= 44, tap
        download_first_classic(p)
        shot(p, '17-phone-reader')
        pg = p.locator('#r-page').inner_text()
        box = p.locator('#viewer').bounding_box()
        p.mouse.click(box['x'] + box['width'] - 20, box['y'] + box['height'] / 2)  # right tap zone
        expect(p.locator('#r-page')).not_to_have_text(pg)
        assert no_hscroll(p)
    _(p)
    c.close()

    # ---- Offline ----
    c = new_context(browser)
    p = watch(c.new_page(), 'offline')
    @test('Offline: app shell and a downloaded book reopen with no network after one online visit')
    def _(p):
        p.goto(BASE + '/')
        p.evaluate('navigator.serviceWorker.ready.then(() => true)')
        p.reload(); p.wait_for_timeout(500)
        assert p.evaluate('!!navigator.serviceWorker.controller'), 'service worker not controlling'
        download_first_classic(p)
        p.locator('[data-act="close"]').first.click()
        c.set_offline(True)
        p.goto(BASE + '/#/shelf')
        p.reload()
        expect(p.locator('#offline')).to_be_visible()
        expect(p.locator('.shelf-card')).to_have_count(1)
        p.locator('.shelf-card .book-card').first.click()
        wait_reader(p)
        expect(frame(p).locator('h1').first).to_be_visible()
        p.locator('[data-act="close"]').first.click()
        p.goto(BASE + '/#/search?q=dracula')
        expect(p.locator('#results')).to_contain_text(re.compile("offline", re.I))
        shot(p, '18-offline-shelf')
        c.set_offline(False)
    _(p)
    c.close()

def manifest_check(browser):
    c = browser.new_context()
    p = c.new_page()
    @test('Installable PWA: manifest, icons, and service worker are valid')
    def _(p):
        p.goto(BASE + '/')
        m = p.evaluate("fetch('/manifest.webmanifest').then(r => r.json())")
        assert m['name'] == 'Mavis Library' and m['display'] == 'standalone' and m['start_url']
        sizes = {i['sizes'] for i in m['icons']}
        assert '192x192' in sizes and '512x512' in sizes and any(i.get('purpose') == 'maskable' for i in m['icons'])
        for i in m['icons']:
            st = p.evaluate(f"fetch('{i['src']}').then(r => r.status)")
            assert st == 200, i['src']
        reg = p.evaluate("navigator.serviceWorker.ready.then(r => !!r.active)")
        assert reg
    _(p)
    c.close()

# ======================================================================
def run_accounts(browser):
    def sign(page, mode, email, pw='correct-horse-9', ack=True):
        page.goto(AUTH + '/#/account')
        if mode == 'up':
            page.get_by_role('button', name='Create account').first.click()
        page.fill('#email', email); page.fill('#password', pw)
        page.locator('#auth-submit').click()
        if mode == 'up':
            expect(page.get_by_role('heading', name='Save your recovery code')).to_be_visible(timeout=10000)
            code = page.locator('#rcode').inner_text().strip()
            if ack:
                expect(page.get_by_role('button', name='Continue')).to_be_disabled()
                page.check('#saved')
                page.get_by_role('button', name='Continue').click()
            return code

    d1 = new_context(browser)
    p1 = watch(d1.new_page(), 'device1')
    @test('Guest shelf → sign-up → migration into the account and push to the server')
    def _(p1):
        p1.goto(AUTH + '/#/book/gutenberg:84')
        p1.get_by_role('button', name='Want to read').click()
        expect(p1.locator('.toast').filter(has_text='Want to read')).to_be_visible()
        code = sign(p1, 'up', 'ada@example.test', ack=False)
        assert re.fullmatch(r'[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}', code), code
        globals()['_ada_code'] = code
        shot(p1, '19a-recovery-code')
        p1.check('#saved')
        p1.get_by_role('button', name='Continue').click()
        dlg = p1.get_by_role('dialog', name='Bring your guest shelf along?')
        expect(dlg).to_be_visible(timeout=10000)
        shot(p1, '19-guest-migration')
        dlg.get_by_role('button', name='Move to my account').click()
        expect(p1.locator('.toast').filter(has_text='Moved 1 book')).to_be_visible()
        expect(p1.locator('.account')).to_contain_text('ada@example.test')
        wait_until(p1, "document.querySelector('#sync-state')?.textContent.includes('Up to date')", timeout=10000)
        log = p1.evaluate("fetch('/__test/sync-log').then(r => r.json())")
        assert any(e['method'] == 'POST' and e['bytes'] > 50 for e in log), log
        shot(p1, '20-account-signed-in')
    _(p1)

    @test('Reading progress and notes sync to a second device; book files do not')
    def _(p1):
        download_first_classic_auth(p1)
        p1.locator('[data-act="toc"]').click()
        p1.get_by_role('dialog', name='Contents').get_by_role('button', name='Notes in the Margins').click()
        expect(p1.locator('#r-chapter')).to_have_text('Notes in the Margins')
        p1.locator('[data-act="bookmark"]').click()
        expect(p1.locator('[data-act="bookmark"]')).to_have_attribute('aria-pressed', 'true')
        p1.wait_for_timeout(1500)
        p1.locator('[data-act="close"]').first.click()
        p1.wait_for_url(lambda u: '/read/' not in u)
        p1.goto(AUTH + '/#/account'); p1.get_by_role('button', name='Sync now').click()
        wait_until(p1, "document.querySelector('#sync-state')?.textContent.includes('Up to date')", timeout=10000)
        d2 = new_context(browser)
        p2 = watch(d2.new_page(), 'device2')
        sign(p2, 'in', 'ada@example.test')
        expect(p2.locator('.account')).to_contain_text('ada@example.test', timeout=10000)
        wait_until(p2, "document.querySelector('#sync-state')?.textContent.includes('Up to date')", timeout=10000)
        p2.goto(AUTH + '/#/shelf')
        expect(p2.locator('.shelf-card')).to_have_count(2)
        pp = p2.locator('.shelf-card').filter(has_text='Pride and Prejudice')
        expect(pp).not_to_contain_text('On device')
        pp.locator('.book-card').click()
        expect(p2.get_by_role('button', name=re.compile('Download'))).to_be_visible()
        p2.get_by_role('button', name=re.compile('Download')).click()
        p2.wait_for_url(re.compile(r'#/read/'), timeout=15000)
        wait_reader(p2)
        expect(p2.locator('#r-chapter')).to_have_text('Notes in the Margins')
        expect(p2.locator('[data-act="bookmark"]')).to_have_attribute('aria-pressed', 'true')
        shot(p2, '21-device2-synced-position')
        p2.locator('[data-act="close"]').first.click()
        p2.wait_for_url(lambda u: '/read/' not in u)
        globals()['_p2'] = (d2, p2)
    _(p1)

    @test('Conflict: the most recent change wins on both devices')
    def _(p1):
        d2, p2 = globals()['_p2']
        p1.goto(AUTH + '/#/book/gutenberg:84'); p1.select_option('#status', 'finished')
        p1.wait_for_timeout(300)
        p2.goto(AUTH + '/#/book/gutenberg:84'); p2.select_option('#status', 'reading')
        for p in (p2, p1, p2, p1):
            p.goto(AUTH + '/#/account'); p.get_by_role('button', name='Sync now').click()
            wait_until(p, "document.querySelector('#sync-state')?.textContent.includes('Up to date')", timeout=10000)
        for p in (p1, p2):
            p.goto(AUTH + '/#/book/gutenberg:84')
            expect(p.locator('#status')).to_have_value('reading')
    _(p1)

    @test("Another account cannot see this user's shelf")
    def _(p1):
        d3 = new_context(browser)
        p3 = watch(d3.new_page(), 'device3')
        sign(p3, 'up', 'grace@example.test')
        expect(p3.locator('.account')).to_contain_text('grace@example.test', timeout=10000)
        wait_until(p3, "document.querySelector('#sync-state')?.textContent.includes('Up to date')", timeout=10000)
        p3.goto(AUTH + '/#/shelf')
        expect(p3.locator('#shelf-body')).to_contain_text('ready for its first book')
        d3.close()
    _(p1)

    @test('Sign-out (with remove-from-device), wrong password, and password reset with the recovery code')
    def _(p1):
        d2, p2 = globals()['_p2']
        p2.goto(AUTH + '/#/account')
        p2.check('#wipe')
        p2.get_by_role('button', name='Sign out', exact=True).click()
        expect(p2.locator('.toast').filter(has_text='removed your data')).to_be_visible()
        p2.goto(AUTH + '/#/shelf')
        expect(p2.locator('#shelf-body')).to_contain_text('ready for its first book')
        sign(p2, 'in', 'ada@example.test', 'wrong-password-1')
        expect(p2.locator('#auth-error')).to_contain_text('don’t match')
        # Forgot password → reset with the recovery code saved at sign-up.
        p2.get_by_role('button', name='Forgot your password?').click()
        p2.fill('#remail', 'ada@example.test')
        p2.fill('#rcodein', 'AAAA-BBBB-CCCC-DDDD'); p2.fill('#rpw', 'brand-new-pass-2')
        p2.get_by_role('button', name='Reset password').click()
        expect(p2.locator('#rerr')).to_contain_text('don’t match')
        p2.fill('#rcodein', globals()['_ada_code'].lower())
        p2.get_by_role('button', name='Reset password').click()
        expect(p2.get_by_role('heading', name='Your new recovery code')).to_be_visible(timeout=10000)
        new_code = p2.locator('#rcode').inner_text().strip()
        assert new_code != globals()['_ada_code']
        shot(p2, '22-recovery-reset')
        p2.check('#saved'); p2.get_by_role('button', name='Continue').click()
        expect(p2.locator('.account')).to_contain_text('ada@example.test')
        wait_until(p2, "document.querySelector('#sync-state')?.textContent.includes('Up to date')", timeout=10000)
        p2.goto(AUTH + '/#/shelf')
        expect(p2.locator('.shelf-card')).to_have_count(2)
        # The reset signed out device 1: its next sync is refused and it drops to guest.
        p1.goto(AUTH + '/#/account'); p1.reload()
        expect(p1.locator('.toast').filter(has_text='session ended')).to_be_visible(timeout=10000)
        expect(p1.locator('#auth-form')).to_be_visible()
        d2.close()
    _(p1)
    d1.close()

def download_first_classic_auth(page):
    page.goto(AUTH + '/#/book/gutenberg:1342')
    page.get_by_role('button', name=re.compile('Download')).click()
    page.wait_for_url(re.compile(r'#/read/'), timeout=15000)
    wait_reader(page)

# ======================================================================
FEAT = 'http://localhost:4323'

def run_v2(browser):
    """0.2 features against a server with cloud voice, AI assistant, and Jev switched on (fixtures)."""
    ctx = new_context(browser)
    page = watch(ctx.new_page(), 'v2')

    @test('Bible opens at a reference with verse focus, chapter navigation, and offline caching')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3?v=16')
        expect(page.locator('.verses .v')).to_have_count(36)
        expect(page.locator('#v16')).to_contain_text('For God so loved the world')
        expect(page.locator('#v16')).to_have_class(re.compile('focus'))
        page.get_by_role('button', name=re.compile('Next')).last.click()
        expect(page.locator('#ch-title')).to_contain_text('John 4')
        wait_until(page, "caches.has('mavis-bible-v1')", timeout=15000)
        shot(page, '23-bible')
    _(page)

    @test('Verse lookup parses references like "1 Cor 13:4-7" and "ps 23"')
    def _(page):
        page.fill('#lookup-q', '1 Cor 13:4-7'); page.keyboard.press('Enter')
        expect(page).to_have_url(re.compile(r'#/bible/1Cor/13\?v=4&ve=7'))
        expect(page.locator('.v.focus')).to_have_count(4)
        page.fill('#lookup-q', 'ps 23'); page.keyboard.press('Enter')
        expect(page.locator('#ch-title')).to_contain_text('Psalms 23')
        expect(page.locator('#v1')).to_contain_text('The Lord is my shepherd')
    _(page)

    @test('Bible verses: highlight, save as quote, note, copy with reference — and they persist')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3')
        page.locator('#v16').click()
        expect(page.locator('#verse-bar')).to_be_visible()
        page.locator('#v17').click()  # extends the selection
        expect(page.locator('#verse-bar')).to_contain_text('John 3:16–17')
        page.get_by_role('button', name='Highlight mint').click()
        expect(page.locator('#v16')).to_have_class(re.compile('hl-mint'))
        page.locator('#v16').click()
        page.get_by_role('button', name=re.compile('Save quote')).click()
        expect(page.locator('.toast').filter(has_text='Saved to your quotes')).to_be_visible()
        page.locator('#v3').click()
        page.get_by_role('button', name='Note').click()
        page.locator('#bnote').fill('Born again: compare 1 Peter 1:23.')
        page.get_by_role('button', name='Save note').click()
        expect(page.locator('#v3 .v-note')).to_be_visible()
        page.reload()
        expect(page.locator('#v16')).to_have_class(re.compile('hl-mint'))
        expect(page.locator('#v16 .v-ico')).to_be_visible()
        page.goto(FEAT + '/#/quotes')
        expect(page.locator('.quote-card')).to_have_count(1)
        expect(page.locator('.quote-card')).to_contain_text('For God so loved the world')
        expect(page.locator('.quote-card')).to_contain_text('John 3:16 (KJV)')
        page.get_by_role('tab', name=re.compile('Notes')).click()
        expect(page.locator('.quote-card')).to_contain_text('Born again')
        shot(page, '24-saved-quotes')
        page.get_by_role('tab', name=re.compile('Quotes')).click()
        page.locator('.quote-card a').first.click()
        expect(page).to_have_url(re.compile(r'#/bible/John/3\?v=16'))
    _(page)

    @test("Strong's concordance: tap a KJV word for Greek/Hebrew, then list every verse using it")
    def _(page):
        page.goto(FEAT + '/#/bible/John/3')
        page.get_by_role('button', name=re.compile('Strong’s numbers')).click()
        page.locator('#v16 .w', has_text='God').first.click()
        sheet = page.get_by_role('dialog', name='Strong’s concordance')
        expect(sheet).to_contain_text('G2316')
        expect(sheet.locator('.lex').first).to_contain_text('theos')
        shot(page, '25-strongs')
        sheet.get_by_role('button', name=re.compile('Every verse with G2316')).click()
        expect(page.locator('.result-count')).to_contain_text(re.compile(r'[\d,]{3,} verses use G2316'), timeout=20000)
        expect(page.locator('.hits li').first).to_contain_text('Matthew')
        page.get_by_role('button', name=re.compile('Strong’s numbers')).count()  # stays on search page
    _(page)

    @test('Concordance word and phrase search with scope, counts by book, and highlighted matches')
    def _(page):
        page.goto(FEAT + '/#/bible/search?q=%22living%20water%22')
        expect(page.locator('.result-count')).to_contain_text('verse', timeout=20000)
        expect(page.locator('.hits')).to_contain_text('John 4:10')
        expect(page.locator('.hits mark').first).to_have_text(re.compile('living water', re.I))
        page.select_option('#bs-scope', 'OT')
        expect(page.locator('.hits')).to_contain_text('Jeremiah')
        page.goto(FEAT + '/#/bible/search?q=faith%20hope&scope=NT')
        expect(page.locator('.hits li').first).to_be_visible(timeout=20000)
        shot(page, '26-bible-search')
    _(page)

    @test('Cross-references and translation compare for a verse')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3')
        page.locator('#v16').click()
        page.get_by_role('button', name='Cross-refs').click()
        d = page.get_by_role('dialog', name=re.compile('Cross-references'))
        expect(d.locator('.xref').first).to_be_visible()
        expect(d).to_contain_text('Romans 5:8')
        page.keyboard.press('Escape')
        expect(page.locator('#verse-bar')).to_be_visible()  # selection is kept
        page.get_by_role('button', name='Compare').click()
        d = page.get_by_role('dialog', name=re.compile('Compare'))
        expect(d).to_contain_text('only begotten Son')
        expect(d).to_contain_text('one and only Son')
        page.keyboard.press('Escape')
    _(page)

    @test('Church display mode shows large verses, steps with arrow keys, exits with Escape')
    def _(page):
        expect(page.locator('#verse-bar')).to_contain_text('John 3:16')  # still selected from the previous check
        page.get_by_role('button', name='Display').click()
        expect(page.locator('.present-text')).to_contain_text('For God so loved')
        expect(page.locator('.present-ref')).to_have_text('John 3:16 · KJV')
        shot(page, '27-display-mode')
        page.keyboard.press('ArrowRight')
        expect(page.locator('.present-ref')).to_have_text('John 3:17 · KJV')
        page.keyboard.press('Escape')
        expect(page.locator('.present')).to_have_count(0)
    _(page)

    @test('Translations: the Berean Standard Bible with section headings and translators’ notes, side by side with the KJV')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3?fresh=tr')
        page.select_option('#tr-pick', 'BSB')
        expect(page.locator('#v16')).to_contain_text('one and only Son', timeout=10000)
        expect(page.locator('.v-heading').first).to_have_text('Jesus and Nicodemus')
        page.locator('#v16 [data-fn]').click()
        expect(page.get_by_role('dialog', name=re.compile('Note'))).to_contain_text('Or his only begotten')
        page.keyboard.press('Escape')
        page.select_option('#tr2-pick', 'kjv')
        expect(page.locator('#v16 .tr-w')).to_contain_text('only begotten Son')
        expect(page.locator('#v16 .tr-k .trl')).to_have_text('BSB')
        expect(page.locator('#bible-credits')).to_contain_text('Berean Standard Bible')
        shot(page, '36-bible-bsb-parallel')
        page.select_option('#tr2-pick', '')
        page.select_option('#tr-pick', 'kjv')
        expect(page.locator('#v16')).to_contain_text('only begotten Son')
    _(page)

    @test('Original language: Greek and Hebrew word by word, with transliteration, gloss, Strong’s, and grammar')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3?fresh=il')
        page.locator('#v16 .vn').click()
        page.get_by_role('button', name='Original').click()
        d = page.get_by_role('dialog', name=re.compile('Original language'))
        expect(d.locator('.il-w').first).to_be_visible(timeout=10000)
        expect(d).to_contain_text('οὕτως')
        expect(d).to_contain_text('houtōs')
        expect(d).to_contain_text('Thus')
        expect(d).to_contain_text('Verb · aorist · active · indicative')
        shot(page, '37-interlinear-greek')
        d.locator('.il-s', has_text='G25').first.click()
        expect(page.get_by_role('dialog', name='Strong’s concordance')).to_contain_text('G25')
        page.keyboard.press('Escape'); page.keyboard.press('Escape')
        page.goto(FEAT + '/#/bible/Ps/23')
        page.locator('#v1 .vn').click()
        page.get_by_role('button', name='Original').click()
        d = page.get_by_role('dialog', name=re.compile('Original language'))
        expect(d.locator('.il-words.rtl')).to_be_visible(timeout=10000)
        expect(d).to_contain_text('H7462')
        expect(d).to_contain_text('Qal')
        page.keyboard.press('Escape')
    _(page)

    @test('Commentary: Matthew Henry on the passage around a verse, and other commentaries')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3?fresh=cm')
        page.locator('#v16 .vn').click()
        page.get_by_role('button', name='Commentary', exact=True).click()
        d = page.get_by_role('dialog', name=re.compile('Commentary'))
        expect(d.locator('details[open] summary')).to_have_text('John 3:1–21', timeout=10000)
        expect(d).to_contain_text('matthew-henry fixture comment on JHN 3:1-21')
        expect(d).to_contain_text('Public domain')
        d.locator('#cm-pick').select_option('jamieson-fausset-brown')
        expect(d).to_contain_text('jamieson-fausset-brown fixture comment')
        shot(page, '38-commentary')
        page.keyboard.press('Escape')
    _(page)

    @test('Topics: Nave’s subjects for a verse, a topic’s passages read in place, and topic search')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3?fresh=tp')
        page.locator('#v16 .vn').click()
        page.get_by_role('button', name='Topics', exact=True).click()
        d = page.get_by_role('dialog', name=re.compile('Topics'))
        expect(d.get_by_role('link', name=re.compile('^Faith'))).to_be_visible(timeout=10000)
        d.get_by_role('link', name=re.compile('^Faith')).click()
        expect(page.get_by_role('heading', name='Faith', exact=True)).to_be_visible(timeout=10000)
        page.get_by_role('button', name='Psalms 2:12').first.click()
        expect(page.locator('.ref-text:not([hidden])').first).to_contain_text('Kiss the Son')
        shot(page, '39-topic')
        page.goto(FEAT + '/#/bible/topics')
        page.fill('#tq', 'forgiv')
        expect(page.locator('.topic-list')).to_contain_text('Forgiveness')
    _(page)

    @test('Reading plans: start a plan, today’s chapter shows a banner, mark it read, progress and streak update')
    def _(page):
        page.goto(FEAT + '/#/bible/plans')
        page.locator('.plan-item', has_text='A Proverb a day').get_by_role('button', name='Start').click()
        card = page.locator('.plan-card')
        expect(card).to_contain_text('Day 1')
        expect(card.locator('.plan-today')).to_have_text('Proverbs 1')
        card.get_by_role('link', name=re.compile('Read')).click()
        expect(page.locator('#ch-title')).to_contain_text('Proverbs 1')
        banner = page.locator('.plan-banner')
        expect(banner).to_contain_text('Day 1: Proverbs 1')
        banner.get_by_role('button', name='Mark as read').click()
        expect(page.locator('.toast').filter(has_text='marked')).to_be_visible()
        expect(page.locator('.plan-banner')).to_have_count(0)
        page.goto(FEAT + '/#/bible/plans')
        expect(card).to_contain_text('Day 2')
        expect(card.locator('.streak')).to_contain_text('1')
        expect(card).to_contain_text('1 of 31 days')
        shot(page, '40-reading-plan')
        card.get_by_role('button', name='Stop this plan').click()
        page.get_by_role('dialog', name='Stop this plan?').get_by_role('button', name='Stop plan').click()
        expect(page.locator('.plan-card')).to_have_count(0)
    _(page)

    @test('Bible read-aloud with the device voice follows verses and continues into the next chapter')
    def _(page):
        page.goto(FEAT + '/#/bible/Jude/1')
        page.evaluate('window.__utter = []; window.__ttsDelay = 5')
        page.get_by_role('button', name='Listen to this chapter').click()
        wait_until(page, "window.__utter.length > 3")
        assert page.evaluate('window.__utter[0]') == 'Jude, chapter 1.'
        expect(page.locator('#listen-bar')).to_be_visible()
        wait_until(page, "window.__utter.some(s => s.startsWith('Revelation, chapter 1'))", timeout=20000)
        expect(page.locator('#ch-title')).to_contain_text('Revelation 1')
        page.get_by_role('button', name='Stop listening').click()
        n = page.evaluate('window.__utter.length'); page.wait_for_timeout(400)
        assert page.evaluate('window.__utter.length') == n
    _(page)

    @test('Owner access code unlocks cloud voice, AI, and Jev; a wrong code is refused')
    def _(page):
        page.goto(FEAT + '/#/settings')
        expect(page.locator('#feat-list')).to_contain_text('Fish Audio')
        page.fill('#access-code', 'nope'); page.get_by_role('button', name='Save code').click()
        expect(page.locator('#code-msg')).to_contain_text('didn’t match')
        page.fill('#access-code', 'test-code'); page.get_by_role('button', name='Save code').click()
        expect(page.locator('#code-msg')).to_contain_text('Code accepted')
        expect(page.locator('#feat-list')).to_contain_text('Owner access')
        page.get_by_role('button', name='Cloud voice').click()
        shot(page, '28-settings-owner')
    _(page)

    @test('Car mode with the cloud voice: MP3 audio from /api/tts plays, big controls pause and exit')
    def _(page):
        page.goto(FEAT + '/#/bible/Ps/117')
        page.get_by_role('button', name='Listen to this chapter').click()
        page.get_by_role('button', name='Car mode').click()
        car = page.get_by_role('dialog', name='Car mode')
        expect(car).to_be_visible()
        expect(car).to_contain_text('lock the phone')
        wait_until(page, "fetch('/__test/tts-calls').then(r => r.json()).then(n => n > 0)", timeout=10000)
        wait_until(page, "['playing','loading'].includes(window.__mavisNarrator && window.__mavisNarrator.state)")
        shot(page, '29-car-mode')
        car.get_by_role('button', name='Pause').click()
        expect(car.locator('[data-status]')).to_have_text('Paused')
        car.get_by_role('button', name=re.compile('Exit car mode')).click()
        expect(page.locator('.carmode')).to_have_count(0)
        page.get_by_role('button', name='Stop listening').click()
    _(page)

    @test('Ask Mavis: consent first, answers about the open chapter, and can start read-aloud')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3')
        page.get_by_role('button', name='Ask Mavis about this chapter').click()
        page.get_by_role('dialog', name='Before you ask').get_by_role('button', name='Got it').click()
        d = page.get_by_role('dialog', name='Ask Mavis')
        d.get_by_role('button', name='Summarize this chapter').click()
        expect(d.locator('.msg-assistant')).to_have_text('Fixture answer about the Bible.')
        shot(page, '30-ask-mavis')
        d.locator('#chat-q').fill('Please read it to me'); d.locator('#chat-q').press('Enter')
        expect(page.locator('#listen-bar')).to_be_visible()
        page.get_by_role('button', name='Stop listening').click()
    _(page)

    @test('Books: save a quote from a selection, share/copy with citation, and jump back from Saved quotes')
    def _(page):
        download_first_classic_at(page, FEAT)
        page.locator('[data-act="toc"]').click()
        page.get_by_role('dialog', name='Contents').get_by_role('button', name='Fog Over the Harbor').click()
        page.wait_for_timeout(400)
        frame(page).locator('body').evaluate("""b => { const p = b.ownerDocument.querySelectorAll('p')[1]; const t = p.firstChild; const r = b.ownerDocument.createRange(); r.setStart(t, 0); r.setEnd(t, 40); const s = b.ownerDocument.getSelection(); s.removeAllRanges(); s.addRange(r); }""")
        page.get_by_role('button', name='Save as a quote').click()
        expect(page.locator('.toast').filter(has_text='Saved to your quotes')).to_be_visible()
        page.wait_for_timeout(600)
        page.locator('[data-act="close"]').first.click()
        page.goto(FEAT + '/#/quotes')
        card = page.locator('.quote-card').filter(has_text='Pride and Prejudice')
        expect(card).to_contain_text('Fog Over the Harbor')
        card.locator('a').click()
        wait_reader(page)
        expect(page.locator('#r-chapter')).to_have_text('Fog Over the Harbor')
    _(page)

    @test('Ask Mavis inside a book sends the chapter and answers')
    def _(page):
        page.get_by_role('button', name='Ask Mavis about this book').click()
        d = page.get_by_role('dialog', name='Ask Mavis')
        d.locator('#chat-q').fill('Who keeps the lantern?'); d.locator('#chat-q').press('Enter')
        expect(d.locator('.msg-assistant')).to_have_text('Fixture answer about the book.')
        page.keyboard.press('Escape')
    _(page)

    @test('Cloud voice in a book reads the chapter as audio and follows along')
    def _(page):
        page.locator('[data-act="tts"]').click()
        expect(page.locator('#tts-engine')).to_have_value('cloud')
        before = page.evaluate("fetch('/__test/tts-calls').then(r => r.json())")
        page.get_by_role('button', name='Start reading aloud').click()
        wait_until(page, f"fetch('/__test/tts-calls').then(r => r.json()).then(n => n > {before})", timeout=10000)
        expect(page.locator('#tts-status')).to_contain_text(re.compile('Cloud voice|Preparing'))
        page.get_by_role('button', name='Stop reading aloud').click()
        page.locator('[data-act="close"]').first.click()
    _(page)

    @test('Commentary summarized by Ask Mavis')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3?fresh=cm2')
        page.locator('#v16 .vn').click()
        page.get_by_role('button', name='Commentary', exact=True).click()
        d = page.get_by_role('dialog', name=re.compile('Commentary'))
        d.locator('#cm-pick').select_option('matthew-henry')
        expect(d.locator('details[open] summary')).to_have_text('John 3:1–21', timeout=10000)
        d.locator('details[open]').get_by_role('button', name=re.compile('Summarize')).click()
        a = page.get_by_role('dialog', name='Ask Mavis')
        expect(a.locator('.msg-user')).to_contain_text('Summarize what Matthew Henry says')
        expect(a.locator('.msg-assistant')).to_contain_text('Fixture answer', timeout=10000)
        page.keyboard.press('Escape')
    _(page)

    @test('Reading companion: picture this page, a spoiler-free spoken recap with cached chapter notes, and a character map')
    def _(page):
        page.goto(FEAT + '/#/read/' + 'gutenberg%3A1342')
        wait_reader(page)
        page.locator('[data-act="toc"]').click()
        page.get_by_role('dialog', name='Contents').get_by_role('button', name='Notes in the Margins').click()
        expect(page.locator('#r-chapter')).to_have_text('Notes in the Margins')
        page.wait_for_timeout(400)
        page.get_by_role('button', name=re.compile('^Reading companion')).click()
        d = page.get_by_role('dialog', name='Reading companion')
        d.get_by_role('button', name=re.compile('Picture this page')).click()
        viewer = page.get_by_role('dialog', name='Notes in the Margins')
        expect(viewer.locator('img')).to_be_visible(timeout=20000)
        info = page.evaluate("fetch('/__test/openai').then(r => r.json())")
        assert 'Absolutely no text' in info['imagePrompt'] and 'Notes in the Margins' in info['imagePrompt'], info['imagePrompt'][:300]
        shot(page, '41-picture-this')
        viewer.get_by_role('button', name='Close').click()
        expect(d.locator('.pic-thumb')).to_have_count(1)
        d.get_by_role('tab', name=re.compile('Story so far')).click()
        d.get_by_role('button', name='Catch me up').click()
        expect(d.locator('.recap')).to_contain_text('ninety-one steps', timeout=20000)
        first = page.evaluate("fetch('/__test/openai').then(r => r.json())")['summaries']
        assert first == 2, f'expected notes for the 2 finished chapters, got {first}'
        d.get_by_role('button', name='Catch me up').click()
        expect(d.locator('.recap')).to_contain_text('ninety-one steps', timeout=20000)
        assert page.evaluate("fetch('/__test/openai').then(r => r.json())")['summaries'] == first, 'chapter notes should be reused'
        d.get_by_role('button', name='Listen').click()
        expect(d.get_by_role('button', name='Stop')).to_be_visible(timeout=10000)
        d.get_by_role('button', name='Stop').click()
        shot(page, '42-story-so-far')
        d.get_by_role('tab', name=re.compile('Characters')).click()
        d.get_by_role('button', name=re.compile('Build my character list')).click()
        expect(d.locator('.person')).to_have_count(2, timeout=20000)
        expect(d.locator('.relmap')).to_be_visible()
        expect(d.locator('.person').first).to_contain_text('Lighthouse keeper')
        shot(page, '43-characters')
        page.keyboard.press('Escape')
        page.locator('[data-act="close"]').first.click()
    _(page)

    @test('Voices: choose a narrator from the voice library, and make a private copy of your own voice (with consent)')
    def _(page):
        page.goto(FEAT + '/#/settings')
        panel = page.locator('#voices-panel')
        expect(panel).to_contain_text('Narrator', timeout=10000)
        panel.get_by_role('button', name=re.compile('Choose a narrator')).click()
        d = page.get_by_role('dialog', name='Choose a narrator')
        expect(d.locator('.voice-item')).to_have_count(4)
        d.get_by_role('button', name='Female', exact=True).click()
        expect(d.locator('.voice-item')).to_have_count(2)
        d.locator('.voice-item', has_text='Calm Reflective Voice').get_by_role('button', name='Choose').click()
        expect(panel).to_contain_text('Calm Reflective Voice')
        panel.get_by_role('button', name=re.compile('Record my voice')).click()
        r = page.get_by_role('dialog', name='Record your voice')
        expect(r.locator('.read-this')).to_contain_text('steady and clear')
        r.locator('#rfile').set_input_files(str(FILES / 'voice.wav'))
        expect(r.get_by_role('button', name='Create my voice')).to_be_disabled()
        r.locator('#rconsent').check()
        r.locator('#rtitle').fill('Dad reading')
        shot(page, '44-record-voice')
        r.get_by_role('button', name='Create my voice').click()
        expect(page.locator('.toast').filter(has_text='is ready')).to_be_visible(timeout=10000)
        expect(panel).to_contain_text('Dad reading')
        expect(panel.locator('.setting-row')).to_contain_text('Dad reading')
        info = page.evaluate("fetch('/__test/fish').then(r => r.json())")
        assert info['create'] == {'type': 'tts', 'visibility': 'private', 'title': 'Dad reading', 'trainMode': 'fast', 'bytes': info['create']['bytes']} and info['create']['bytes'] > 30000, info
    _(page)

    @test('Full cast: each character gets a voice that fits, the narrator uses your voice, and the cast can be changed')
    def _(page):
        page.goto(FEAT + '/#/read/' + 'gutenberg%3A1342')
        wait_reader(page)
        page.locator('[data-act="toc"]').click()
        page.get_by_role('dialog', name='Contents').get_by_role('button', name='Fog Over the Harbor').click()
        expect(page.locator('#r-chapter')).to_have_text('Fog Over the Harbor')
        page.wait_for_timeout(400)
        page.locator('[data-act="tts"]').click()
        expect(page.locator('#tts-engine')).to_have_value('cloud')
        page.locator('#tts-fullcast').check()
        before = len(page.evaluate("fetch('/__test/fish').then(r => r.json())")['voices'])
        page.get_by_role('button', name='Start reading aloud').click()
        wait_until(page, f"fetch('/__test/fish').then(r => r.json()).then(j => j.voices.length >= {before + 4})", timeout=20000)
        used = page.evaluate("fetch('/__test/fish').then(r => r.json())")['voices'][before:]
        assert 'b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2' in used, f'the old ferryman should get the older male voice: {used}'
        assert 'c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3' in used, f'the keeper should get a female voice: {used}'
        assert 'e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5' in used, f'narration should use the chosen narrator (your voice): {used}'
        page.get_by_role('button', name='Stop reading aloud').click()
        page.get_by_role('button', name='Cast…').click()
        c = page.get_by_role('dialog', name='Cast')
        expect(c).to_contain_text('The Ferryman')
        expect(c).to_contain_text('The Keeper')
        c.locator('[data-cast="The Keeper"]').select_option('d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4')
        expect(page.locator('.toast').filter(has_text='Bright Young Reader')).to_be_visible()
        shot(page, '45-full-cast')
        page.keyboard.press('Escape')
        page.locator('#tts-fullcast').uncheck()
        page.locator('[data-act="close"]').first.click()
    _(page)

    @test('Verse video: a narrated square video of John 3:16 made on the device, previewable and shareable')
    def _(page):
        page.goto(FEAT + '/#/bible/John/3?fresh=vid')
        page.locator('#v16 .vn').click()
        page.get_by_role('button', name='Video', exact=True).click()
        d = page.get_by_role('dialog', name='Make a video')
        expect(d.locator('.vm-text')).to_contain_text('For God so loved')
        d.locator('#vm-format').select_option('square')
        d.locator('#vm-bg').select_option('theme:sea')
        expect(d.locator('#vm-voice')).to_be_checked()
        before = page.evaluate("fetch('/__test/tts-calls').then(r => r.json())")
        d.get_by_role('button', name='Make video').click()
        expect(d.locator('video.vm-preview')).to_be_visible(timeout=30000)
        assert page.evaluate("fetch('/__test/tts-calls').then(r => r.json())") == before + 1, 'one narration request'
        wait_until(page, "document.querySelector('video.vm-preview').readyState >= 1", timeout=10000)
        dims = page.evaluate("(() => { const v = document.querySelector('video.vm-preview'); return [v.videoWidth, v.videoHeight]; })()")
        assert dims == [1080, 1080], dims
        expect(d).to_contain_text(re.compile(r'(MP4|WEBM) · \d+\.\d MB'))
        expect(d.get_by_role('link', name='Save video')).to_have_attribute('download', re.compile(r'John-3-16'))
        shot(page, '46-verse-video')
        page.keyboard.press('Escape')
        page.goto(FEAT + '/#/quotes')
        expect(page.locator('.quote-card [data-video]').first).to_be_visible()
    _(page)

    @test('LibriVox: find human-read recordings of a Gutenberg book, stream one, save it for offline, keep listening offline')
    def _(page):
        import urllib.request
        mp3 = urllib.request.urlopen(FEAT + '/__test/silent.mp3').read()
        ctx.route('https://www.archive.org/**', lambda r: r.fulfill(status=200, content_type='audio/mpeg', body=mp3, headers={'access-control-allow-origin': '*', 'accept-ranges': 'bytes'}))
        page.goto(FEAT + '/#/book/gutenberg:1342')
        panel = page.locator('.ab-panel', has_text='Human-read audiobook')
        panel.get_by_role('button', name='Find recordings').click()
        sel = panel.locator('#lv-v')
        expect(sel.locator('option')).to_have_count(2, timeout=10000)
        expect(sel.locator('option').first).to_contain_text('Elizabeth Klett')
        expect(sel.locator('option').first).to_contain_text('(solo)')
        expect(sel.locator('option').nth(1)).to_contain_text('(group)')
        panel.get_by_role('button', name='Listen').click()
        expect(page).to_have_url(re.compile(r'#/listen/lv%3Agutenberg%3A1342'))
        expect(page.locator('.listen .eyebrow')).to_contain_text('read by Elizabeth Klett')
        wait_until(page, "document.querySelector('.listen')?.classList.contains('is-playing')", timeout=10000)
        expect(page.locator('#l-chapter')).to_contain_text('of 3')
        shot(page, '47-librivox-listen')
        page.get_by_role('button', name='Pause').click()
        page.goto(FEAT + '/#/book/gutenberg:1342')
        panel.get_by_role('button', name='Find recordings').click()
        panel.get_by_role('button', name=re.compile('Save for offline')).click()
        expect(panel).to_contain_text('Saved for offline', timeout=20000)
        ctx.set_offline(True)
        page.goto(FEAT + '/#/listen/lv%3Agutenberg%3A1342')
        page.get_by_role('button', name='Next chapter').click()
        wait_until(page, "document.querySelector('.listen')?.classList.contains('is-playing')", timeout=10000)
        ctx.set_offline(False)
        page.get_by_role('button', name='Pause').click()
        page.goto(FEAT + '/#/shelf')
        card = page.locator('.shelf-card').filter(has_text='Pride and Prejudice')
        expect(card.get_by_role('link', name='Listen to the saved audio')).to_have_attribute('href', re.compile('lv%3A'))
        ctx.unroute('https://www.archive.org/**')
    _(page)

    @test('Offline audiobook: save a whole book as audio, listen, keep playing with no network, resume where you stopped')
    def _(page):
        page.goto(FEAT + '/#/book/gutenberg:1342')
        panel = page.locator('.ab-panel', has_text='Listen offline')
        expect(panel).to_be_visible(timeout=10000)
        panel.get_by_role('button', name=re.compile('Get it as audio')).click()
        expect(panel).to_contain_text('of audio', timeout=20000)
        before = page.evaluate("fetch('/__test/tts-calls').then(r => r.json())")
        panel.get_by_role('button', name='Save audio').click()
        expect(panel).to_contain_text('Whole book saved', timeout=40000)
        saved_calls = page.evaluate("fetch('/__test/tts-calls').then(r => r.json())")
        assert saved_calls - before >= 4, (before, saved_calls)
        shot(page, '34-audio-saved')
        panel.get_by_role('link', name=re.compile('^Listen')).click()
        expect(page).to_have_url(re.compile('#/listen/'))
        expect(page.locator('#l-chapter')).to_contain_text('1 of 4')
        ctx.set_offline(True)
        page.get_by_role('button', name='Play', exact=True).click()
        wait_until(page, "document.querySelector('.listen')?.classList.contains('is-playing')", timeout=10000)
        page.get_by_role('button', name='Next chapter').click()
        expect(page.locator('#l-chapter')).to_contain_text(re.compile('[2-4] of 4'))
        wait_until(page, "document.querySelector('.listen')?.classList.contains('is-playing')", timeout=10000)
        w0 = page.evaluate("parseFloat(document.querySelector('#l-bar').style.width)")
        page.wait_for_timeout(2600)  # each fixture clip is one second: parts advance on their own
        w1 = page.evaluate("parseFloat(document.querySelector('#l-bar').style.width)")
        assert w1 > w0, (w0, w1)
        shot(page, '35-listen-offline')
        page.get_by_role('button', name='Pause').click()
        expect(page.locator('#l-status')).to_have_text('Paused')
        ctx.set_offline(False)
        assert page.evaluate("fetch('/__test/tts-calls').then(r => r.json())") == saved_calls, 'offline playback must not call the voice service'
        page.reload()
        expect(page.locator('#l-chapter')).to_contain_text(re.compile('[2-4] of 4'))
        page.goto(FEAT + '/#/shelf')
        card = page.locator('.shelf-card').filter(has_text='Pride and Prejudice')
        expect(card).to_contain_text('Audio saved')
        expect(card.get_by_role('link', name='Listen to the saved audio')).to_be_visible()
        page.goto(FEAT + '/#/book/gutenberg:1342')
        panel.get_by_role('button', name='Remove audio').click()
        page.get_by_role('dialog', name='Remove the audio?').get_by_role('button', name='Remove audio').click()
        expect(panel).to_contain_text('Get it as audio')
    _(page)

    @test('Picked for you: recommendations from shelf genres, ranked by Jev when available')
    def _(page):
        page.goto(FEAT + '/#/')
        expect(page.locator('#recs-section')).to_be_visible(timeout=15000)
        expect(page.locator('#recs-note')).to_contain_text('Jev')
        assert page.locator('#recs .book-card').count() > 3
        assert page.locator('#recs .book-card', has_text='Pride and Prejudice').count() == 0, 'shelf books must not be recommended'
        expect(page.locator('#votd')).to_contain_text('—')
        shot(page, '31-home-v2', full=True)
    _(page)

    LANTERN = json.dumps([{'word': 'lantern', 'phonetic': '/ˈlæntən/', 'meanings': [{'partOfSpeech': 'noun', 'definitions': [{'definition': 'A lamp with a case that protects the flame.', 'example': 'The lantern glowed in the window.'}], 'synonyms': []}]}])

    def answer_question(page, right=True):
        # Work out the right answer from what the card shows, then pick it (or a wrong one).
        card = page.locator('#p-card')
        text = card.text_content()
        opts = card.locator('[data-opt]')
        ids = [opts.nth(i).get_attribute('data-opt') for i in range(opts.count())]
        if 'What does' in text:
            target = 'harbor' if 'What does harbor' in text else 'lantern'
        elif 'Which word fits' in text:
            target = 'lantern' if 'glowed' in text else 'harbor'
        else:
            target = 'lantern' if 'protects the flame' in text else 'harbor'
        choice = target if right else next(i for i in ids if i != target)
        card.locator(f'[data-opt="{choice}"]').click()
        expect(card.locator('.practice-fb')).to_have_class(re.compile('ok' if right else 'miss'))
        return target

    @test('Word builder: save a word from the dictionary with its sentence, add one by typing, explain it simply, practice, and words move up')
    def _(page):
        page.goto(FEAT + '/#/read/gutenberg%3A1342')
        wait_reader(page)
        page.locator('[data-act="toc"]').click()
        page.get_by_role('dialog', name='Contents').get_by_role('button', name='Fog Over the Harbor').click()
        page.wait_for_timeout(500)
        word = frame(page).locator('body').evaluate("""b => { const d = b.ownerDocument; const p = d.querySelectorAll('p')[1]; const t = p.firstChild;
            const m = /[A-Za-z]{5,}/.exec(t.data); const r = d.createRange(); r.setStart(t, m.index); r.setEnd(t, m.index + m[0].length);
            const s = d.getSelection(); s.removeAllRanges(); s.addRange(r); return m[0]; }""")
        expect(page.locator('#sel')).to_be_visible(timeout=4000)
        page.get_by_role('button', name='Look up in dictionary').click()
        dlg = page.get_by_role('dialog', name='Dictionary')
        expect(dlg).to_contain_text('sheltered expanse')
        expect(dlg).to_contain_text('Saved words keep this sentence')
        dlg.get_by_role('button', name=re.compile('Save “harbor”')).click()
        expect(page.locator('.toast').filter(has_text='Word builder')).to_be_visible()
        expect(dlg.locator('[data-vsave]').first).to_contain_text('Saved')
        shot(page, '48-dictionary-save-word')
        page.keyboard.press('Escape')
        page.locator('[data-act="close"]').first.click()

        page.goto(FEAT + '/#/words')
        card = page.locator('.word-card').filter(has_text='harbor')
        expect(card).to_contain_text('sheltered expanse')
        expect(card.locator('.word-sent')).to_contain_text(word)
        expect(card.get_by_role('link', name=re.compile('Pride and Prejudice'))).to_have_attribute('href', re.compile(r'#/read/gutenberg%3A1342\?at='))
        page.route('https://api.dictionaryapi.dev/**', lambda r: r.fulfill(status=200, content_type='application/json', body=LANTERN, headers={'access-control-allow-origin': '*'}))
        page.fill('#w-new', 'lantern'); page.locator('#w-new').press('Enter')
        page.locator('[data-addsave]').first.click()
        expect(page.locator('.word-card')).to_have_count(2)
        expect(page.locator('#w-stats div').nth(1).locator('b')).to_have_text('2')
        card.get_by_role('button', name='Explain simply').click()
        expect(card).to_contain_text('In simple words: A sheltered place where ships can stay.')
        info = page.evaluate("fetch('/__test/openai').then(r => r.json())")
        assert info['lastWord'] and word.lower() in info['lastWord']['user'], info
        shot(page, '49-word-builder')

        page.get_by_role('link', name=re.compile('Practice 2 words')).click()
        expect(page.locator('#p-count')).to_have_text('1 of 2')
        answer_question(page, right=True)
        page.get_by_role('button', name='Next').click()
        missed = answer_question(page, right=False)
        expect(page.locator('.practice-fb')).to_contain_text(f'Not quite — it’s {missed}')
        shot(page, '50-practice-miss')
        page.get_by_role('button', name='Next').click()
        expect(page.locator('#p-count')).to_have_text('3 of 3')  # the missed word comes back once
        answer_question(page, right=True)
        page.get_by_role('button', name='See how you did').click()
        expect(page.locator('#p-card')).to_contain_text('1 of 2 right the first time')
        page.get_by_role('link', name='Back to my words').click()
        expect(page.locator('#w-stats div').nth(1).locator('b')).to_have_text('0')
        expect(page.get_by_role('link', name=re.compile('Practice anyway'))).to_be_visible()
        expect(page.locator('.word-card .word-dots span.on')).to_have_count(2)  # both words reached box 1
        expect(page.locator('.word-card').first).to_contain_text('Next:')
    _(page)

    ctx.close()

    c = new_context(browser, viewport={'width': 360, 'height': 760}, is_mobile=True, has_touch=True, device_scale_factor=2)
    p = watch(c.new_page(), 'v2-phone')
    @test('Phone at 360px: Bible, search, quotes, and settings fit without sideways scrolling')
    def _(p):
        for route, name in [('/#/bible/Ps/23', '32-phone-bible'), ('/#/bible/search?q=shepherd', None), ('/#/quotes', None), ('/#/settings', None), ('/#/', None)]:
            p.goto(FEAT + route); p.wait_for_timeout(1200)
            assert no_hscroll(p), f'horizontal scroll on {route}'
            if name: shot(p, name)
        p.goto(FEAT + '/#/bible/Ps/23')
        p.locator('#v1').click()
        expect(p.locator('#verse-bar')).to_be_visible()
        shot(p, '33-phone-verse-actions')
        assert no_hscroll(p)
        box = p.locator('#verses').bounding_box()
        p.mouse.move(box['x'] + box['width'] - 10, box['y'] + 60)
        p.touchscreen.tap(box['x'] + 10, box['y'] + 10)
    _(p)
    c.close()

    c = new_context(browser)
    k = watch(c.new_page(), 'kids')
    @test('Kids mode: a grown-up turns it on with a PIN; children’s books only, no store links, kid-safe AI, grown-up screens need the PIN, PIN turns it off')
    def _(k):
        k.goto(FEAT + '/#/book/gutenberg:345')
        k.get_by_role('button', name='Want to read').click()
        k.goto(FEAT + '/#/settings')
        k.fill('#access-code', 'test-code'); k.get_by_role('button', name='Save code').click()
        expect(k.locator('#code-msg')).to_contain_text('Code accepted')
        k.fill('#kn', 'Ada'); k.fill('#kg', '10'); k.fill('#kp', '1234'); k.fill('#kp2', '4321')
        k.get_by_role('button', name='Turn on kids mode').click()
        expect(k.locator('#kids-msg')).to_contain_text('don’t match')
        k.fill('#kp2', '1234')
        k.get_by_role('button', name='Turn on kids mode').click()
        expect(k.locator('h1')).to_have_text('Hi, Ada!')
        expect(k.locator('.brand-name')).to_have_text('Ada’s Library')
        expect(k.locator('.tabbar')).to_contain_text('My words')
        expect(k.locator('.tabbar [data-nav="account"]')).to_have_count(0)
        expect(k.locator('.goal-ring')).to_have_attribute('aria-label', '0 of 10 minutes read today')
        expect(k.locator('#k-shelf')).to_contain_text("Alice's Adventures in Wonderland")
        expect(k.locator('#k-shelf')).not_to_contain_text('Dracula')
        expect(k.locator('.bible-stories')).to_contain_text('David and Goliath')
        assert k.locator('.bible-stories a', has_text='David and Goliath').get_attribute('href') == '#/bible/1Sam/17'
        shot(k, '51-kids-home', full=True)

        with k.expect_request(lambda r: '/api/catalog' in r.url and 'topic=juvenile' in r.url):
            k.goto(FEAT + '/#/search?q=pride')
        expect(k.locator('h1')).to_have_text('Find a story')
        expect(k.get_by_role('tab', name='All books')).to_be_hidden()

        k.goto(FEAT + '/#/shelf')
        expect(k.locator('.book-card', has_text='Dracula')).to_have_count(0)
        k.goto(FEAT + '/#/book/gutenberg:11')
        expect(k.locator('h1')).to_contain_text('Alice')
        expect(k.get_by_text('Buy an ebook')).to_have_count(0)
        k.get_by_role('button', name=re.compile('Download')).click()
        k.wait_for_url(re.compile(r'#/read/'), timeout=15000)
        wait_reader(k)
        size = frame(k).locator('body').evaluate('b => parseFloat(getComputedStyle(b).fontSize)')
        assert size >= 19, f'kids text should start bigger, got {size}px'
        k.get_by_role('button', name='Ask Mavis about this book').click()
        k.get_by_role('dialog', name='Before you ask').get_by_role('button', name='Got it').click()
        d = k.get_by_role('dialog', name='Ask Mavis')
        d.locator('#chat-q').fill('Who is the white rabbit?'); d.locator('#chat-q').press('Enter')
        expect(d.locator('.msg-assistant')).to_have_text('Fixture answer about the book.')
        assert 'kids mode' in k.evaluate("fetch('/__test/openai').then(r => r.json())")['lastSystem']
        k.keyboard.press('Escape')
        k.locator('[data-act="close"]').first.click()
        k.goto(FEAT + '/#/shelf')
        expect(k.locator('.book-card', has_text="Alice's Adventures")).to_have_count(1)
        expect(k.locator('.book-card', has_text='Dracula')).to_have_count(0)

        k.get_by_role('link', name=re.compile('Grown-ups')).click()
        pin = k.get_by_role('dialog', name='Grown-ups only')
        pin.locator('#pin-in').fill('0000'); pin.locator('#pin-in').press('Enter')
        expect(pin.locator('#pin-err')).to_have_text('That PIN isn’t right.')
        shot(k, '52-kids-pin')
        pin.locator('#pin-in').fill('1234'); pin.locator('#pin-in').press('Enter')
        expect(k.locator('#kids-panel')).to_contain_text('Kids mode is on')
        k.fill('#kp-off', '1234'); k.get_by_role('button', name='Turn off kids mode').click()
        expect(k.locator('.tabbar [data-nav="account"]')).to_have_count(1)
        expect(k.locator('.brand-name')).to_have_text('Mavis Library')
        k.goto(FEAT + '/#/shelf')
        expect(k.locator('.book-card', has_text='Dracula')).to_have_count(1)
    _(k)
    c.close()

def download_first_classic_at(page, base):
    page.goto(base + '/#/book/gutenberg:1342')
    page.get_by_role('button', name=re.compile('Download')).click()
    page.wait_for_url(re.compile(r'#/read/'), timeout=15000)
    wait_reader(page)


# ======================================================================
def main():
    subprocess.run(['node', 'tests/e2e/make-files.mjs', str(FILES)], cwd=ROOT, check=True)
    import wave, math
    with wave.open(str(FILES / 'voice.wav'), 'wb') as w:  # 3 s test tone standing in for a voice recording
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes(b''.join(int(8000 * math.sin(2 * math.pi * 220 * i / 16000)).to_bytes(2, 'little', signed=True) for i in range(48000)))
    procs = [
        subprocess.Popen(['node', 'tests/e2e/server.mjs', '--dist', 'dist', '--port', '4321'], cwd=ROOT),
        subprocess.Popen(['node', 'tests/e2e/server.mjs', '--dist', 'dist', '--port', '4322', '--accounts'], cwd=ROOT),
        subprocess.Popen(['node', 'tests/e2e/server.mjs', '--dist', 'dist', '--port', '4323'], cwd=ROOT,
                         env={**os.environ, 'MAVIS_ACCESS_CODE': 'test-code', 'TTS_PROVIDER': 'fish', 'FISH_AUDIO_API_KEY': 'fixture', 'LLM_PROVIDER': 'openai', 'LLM_MODEL': 'gpt-5.6-luna', 'LLM_API_KEY': 'fixture', 'EDENAI_API_KEY': 'fixture'}),
    ]
    time.sleep(2.5)
    started = time.time()
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            print('Public build (guest mode):')
            run_public(browser)
            manifest_check(browser)
            print('Accounts (Netlify Blobs, local server):')
            run_accounts(browser)
            print('0.2 features (cloud voice, AI, Jev on fixtures):')
            run_v2(browser)
            version = browser.version
            browser.close()
    finally:
        for p in procs: p.terminate()
    passed = sum(r['ok'] for r in results)
    lines = [f'# Mavis Library end-to-end results', '',
             f'Run: {time.strftime("%Y-%m-%d %H:%M %Z")} · Chromium {version} (headless, Linux) · {time.time() - started:.0f}s', '',
             f'**{passed} of {len(results)} checks passed.**', '',
             '| Result | Check |', '|---|---|']
    for r in results:
        lines.append(f"| {'✅ pass' if r['ok'] else '❌ fail'} | {r['name']}{(' — ' + r['detail'].replace('|', '/')) if not r['ok'] else ''} |")
    lines += ['', '## Console and CSP problems seen during the run', '']
    lines += [f'- {c}' for c in console_problems] or ['- None.']
    (EVID / 'e2e-report.md').write_text('\n'.join(lines) + '\n')
    print(f'\n{passed}/{len(results)} passed. Console problems: {len(console_problems)}')
    for c in console_problems[:20]: print('  ', c)
    sys.exit(0 if passed == len(results) else 1)

if __name__ == '__main__':
    main()
