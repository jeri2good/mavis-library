"""
Mavis Library end-to-end tests (Playwright + Chromium).

  npm run build
  VITE_SUPABASE_URL=http://localhost:4322/sb VITE_SUPABASE_ANON_KEY=test-anon npx vite build --outDir dist-auth
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

def new_context(browser, *, tts=True, mic='ok', **kw):
    ctx = browser.new_context(viewport=kw.pop('viewport', {'width': 1280, 'height': 860}), **kw)
    if tts: ctx.add_init_script(FAKE_TTS)
    if mic: ctx.add_init_script(fake_recognition(mic))
    ctx.route('https://api.dictionaryapi.dev/**', lambda r: r.fulfill(status=200, content_type='application/json', body=DICT, headers={'access-control-allow-origin': '*'}))
    ctx.route('https://www.gutenberg.org/**', lambda r: r.fulfill(status=404, body=''))
    ctx.route('https://covers.openlibrary.org/**', lambda r: r.fulfill(status=200, content_type='image/png', body=PNG))
    return ctx

def watch(page, label):
    def on_console(m):
        t = m.text
        if 'Blocked script execution' in t:  # the hostile fixture's script being blocked by the sandbox: expected
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
        expect(page.locator('#classics .book-card').first.locator('img')).to_have_count(0)
        assert page.locator('#classics .cover-gen').count() == 16
        assert no_hscroll(page), 'page scrolls horizontally'
        shot(page, '01-home-desktop')
    _(page)

    @test('Search by title/author, pagination, and language filter')
    def _(page):
        page.goto(BASE + '/#/search?q=austen')
        expect(page.locator('.result-count')).to_contain_text('page 1 of')
        n1 = page.locator('#results .book-card').count()
        assert n1 == 32, n1
        page.get_by_role('link', name=re.compile('Next')).click()
        expect(page).to_have_url(re.compile('page=2'))
        expect(page.locator('.result-count')).to_contain_text('page 2 of')
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
        assert len(set(spoken)) / len(spoken) > 0.3
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
        expect(page.locator('.toast').filter(has_text='field-notes')).to_be_visible()
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
    def sign(page, mode, email, pw='correct-horse-9'):
        page.goto(AUTH + '/#/account')
        if mode == 'up':
            page.get_by_role('button', name='Create account').first.click()
        page.fill('#email', email); page.fill('#password', pw)
        page.locator('#auth-submit').click()

    d1 = new_context(browser)
    p1 = watch(d1.new_page(), 'device1')
    @test('Guest shelf → sign-up → migration into the account and push to the server')
    def _(p1):
        p1.goto(AUTH + '/#/book/gutenberg:84')
        p1.get_by_role('button', name='Want to read').click()
        expect(p1.locator('.toast').filter(has_text='Want to read')).to_be_visible()
        sign(p1, 'up', 'ada@example.test')
        dlg = p1.get_by_role('dialog', name='Bring your guest shelf along?')
        expect(dlg).to_be_visible(timeout=10000)
        shot(p1, '19-guest-migration')
        dlg.get_by_role('button', name='Move to my account').click()
        expect(p1.locator('.toast').filter(has_text='Moved 1 book')).to_be_visible()
        expect(p1.locator('.account')).to_contain_text('ada@example.test')
        wait_until(p1, "document.querySelector('#sync-state')?.textContent.includes('Up to date')", timeout=10000)
        log = p1.evaluate("fetch('/__test/supabase-log').then(r => r.json())")
        assert any(e['method'] == 'POST' and e['table'] == 'shelf_items' for e in log), log
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

    @test('Sign-out (with remove-from-device), wrong password, and email-confirmation flows')
    def _(p1):
        d2, p2 = globals()['_p2']
        p2.goto(AUTH + '/#/account')
        p2.check('#wipe')
        p2.get_by_role('button', name='Sign out').click()
        expect(p2.locator('.toast').filter(has_text='removed your data')).to_be_visible()
        p2.goto(AUTH + '/#/shelf')
        expect(p2.locator('#shelf-body')).to_contain_text('ready for its first book')
        sign(p2, 'in', 'ada@example.test', 'wrong-password-1')
        expect(p2.locator('#auth-error')).to_contain_text('don’t match')
        sign(p2, 'up', 'new+confirm@example.test')
        expect(p2.get_by_role('heading', name='Check your email')).to_be_visible()
        shot(p2, '22-check-email')
        d2.close()
    _(p1)
    d1.close()

def download_first_classic_auth(page):
    page.goto(AUTH + '/#/book/gutenberg:1342')
    page.get_by_role('button', name=re.compile('Download')).click()
    page.wait_for_url(re.compile(r'#/read/'), timeout=15000)
    wait_reader(page)

# ======================================================================
def main():
    subprocess.run(['node', 'tests/e2e/make-files.mjs', str(FILES)], cwd=ROOT, check=True)
    procs = [
        subprocess.Popen(['node', 'tests/e2e/server.mjs', '--dist', 'dist', '--port', '4321'], cwd=ROOT),
        subprocess.Popen(['node', 'tests/e2e/server.mjs', '--dist', 'dist-auth', '--port', '4322', '--supabase'], cwd=ROOT),
    ]
    time.sleep(2.5)
    started = time.time()
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            print('Public build (guest mode):')
            run_public(browser)
            manifest_check(browser)
            print('Accounts build (Supabase emulator):')
            run_accounts(browser)
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
