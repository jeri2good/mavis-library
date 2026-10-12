import asyncio
import socket
from unittest.mock import AsyncMock

import pytest

import sources
from sources import MainText, PublicResolver, book_outline, chapter_chunks, extract_article, public_addresses, strip_gutenberg, validate_url


@pytest.mark.parametrize("url", [
    "http://example.com", "file:///etc/passwd", "https://127.0.0.1", "https://10.2.3.4/a", "https://192.168.1.145", "https://169.254.169.254/latest", "https://[::1]", "https://[::ffff:127.0.0.1]", "https://localhost", "https://host.local", "https://host.internal", "https://foo.localhost", "https://2130706433", "https://0x7f000001", "https://0177.0.0.1", "https://0x7f.0.0.1", "https://public.com@127.0.0.1", "https://user:pass@example.com", "https://@example.com", "https://example.com:8080", "https://example.com\\@evil.com", "https://example.com\n", "https://example..com", "https://-bad.example.com", "https://100.64.0.1", "https://8.8.8.8", "https://example.com%00.local", "https://example.com:bad", "https://a.lan", "https://example.com\x00", "https://127.1", "https://[fe80::1%25eth0]", "https://123.456", "https://example.com./@127.0.0.1?x=1\r\n"
])
def test_url_rejected(url):
    with pytest.raises(ValueError, match="public HTTPS"):
        validate_url(url)


@pytest.mark.parametrize("url", ["https://www.gutenberg.org/a", "https://EXAMPLE.com./a?b=c", "https://example.com:443", "https://bücher.de", "https://example.com/path@anything"])
def test_url_allowed(url):
    assert validate_url(url)


@pytest.mark.parametrize("addresses", [[], ["127.0.0.1"], ["10.0.0.1"], ["172.16.1.1"], ["192.168.1.1"], ["169.254.169.254"], ["100.64.1.1"], ["::1"], ["fc00::1"], ["fe80::1"], ["::ffff:127.0.0.1"], ["8.8.8.8", "192.168.1.2"], ["0.0.0.0"], ["224.0.0.1"], ["2001:db8::1"]])
def test_private_dns_rejected(addresses):
    with pytest.raises(ValueError):
        public_addresses(addresses)


def test_public_dns():
    assert public_addresses(["8.8.8.8", "2606:4700:4700::1111"])


def test_resolver_checks_actual_connection(monkeypatch):
    async def run():
        loop = asyncio.get_running_loop()
        lookup = AsyncMock(return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.0.0.1", 443))])
        monkeypatch.setattr(loop, "getaddrinfo", lookup)
        with pytest.raises(ValueError):
            await PublicResolver().resolve("example.com", 443)
        lookup.return_value = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443))]
        resolved = await PublicResolver().resolve("example.com", 443)
        assert resolved[0]["host"] == "8.8.8.8"
    asyncio.run(run())


class FakeResponse:
    def __init__(self, status=200, location=None, chunks=None, length=None):
        self.status = status
        self.headers = {"Location": location} if location else {}
        self.content_length, self.charset = length, "utf-8"
        self.chunks = chunks or [b"hello"]
        self.content = self

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass

    def raise_for_status(self):
        pass

    async def iter_chunked(self, size):
        for chunk in self.chunks:
            yield chunk


def fake_fetch(monkeypatch, responses):
    requests = []

    class Session:
        def __init__(self, **kwargs):
            assert isinstance(kwargs["cookie_jar"], sources.aiohttp.DummyCookieJar)
            assert kwargs["trust_env"] is False
            assert kwargs["headers"]["User-Agent"] == sources.UA
            self.connector = kwargs["connector"]

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            await self.connector.close()

        def get(self, url, allow_redirects):
            assert allow_redirects is False
            requests.append(url)
            return responses.pop(0)

    monkeypatch.setattr(sources.aiohttp, "ClientSession", Session)
    return requests


@pytest.mark.parametrize("destination", ["https://127.0.0.1", "https://host.internal", "http://example.com", "https://169.254.169.254"])
def test_redirect_private(monkeypatch, destination):
    requests = fake_fetch(monkeypatch, [FakeResponse(302, destination)])
    with pytest.raises(ValueError):
        asyncio.run(sources.fetch_url("https://example.com"))
    assert requests == ["https://example.com"]


def test_redirect_limit(monkeypatch):
    requests = fake_fetch(monkeypatch, [FakeResponse(302, "/next") for _ in range(4)])
    with pytest.raises(ValueError, match="Too many redirects"):
        asyncio.run(sources.fetch_url("https://example.com"))
    assert len(requests) == 4


def test_redirect_public(monkeypatch):
    requests = fake_fetch(monkeypatch, [FakeResponse(302, "/article"), FakeResponse()])
    assert asyncio.run(sources.fetch_url("https://example.com")) == ("hello", "https://example.com/article")
    assert len(requests) == 2


@pytest.mark.parametrize("response", [FakeResponse(length=sources.MAX_BYTES + 1), FakeResponse(chunks=[b"a" * sources.MAX_BYTES, b"x"])])
def test_fetch_size_cap(monkeypatch, response):
    fake_fetch(monkeypatch, [response])
    with pytest.raises(ValueError, match="3 MB"):
        asyncio.run(sources.fetch_url("https://example.com"))


def test_article_fixture():
    from pathlib import Path
    title, text = extract_article((Path(__file__).parent / "article.html").read_text())
    assert title == "A Useful Article"
    assert "Vaccines teach immune memory" in text
    assert "second paragraph" in text
    assert all(word not in text for word in ("COOKIE", "ADVERT", "NAVIGATION", "JAVASCRIPT", "OUTSIDE"))


def test_skip_class_recovers():
    assert extract_article('<div class="cookie">COOKIE</div><p>Readable text</p>')[1] == "Readable text"


def test_gutenberg_fixture():
    from pathlib import Path
    text = strip_gutenberg((Path(__file__).parent / "book.txt").read_text())
    assert "LICENSE" not in text and "FOOTER" not in text
    assert [title for title, _ in chapter_chunks(text)] == ["CHAPTER I. Opening", "CHAPTER II. Ending"]


def test_partial_gutenberg_markers():
    assert strip_gutenberg("license\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\nStory") == "Story"
    assert strip_gutenberg("Story\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\nlicense") == "Story"


def test_outline_cap_and_coverage():
    text = "\n".join(f"CHAPTER {i}. Heading\n" + (f"detail{i} " * 10000) for i in range(1, 13))
    sample = book_outline(text)
    assert len(sample) <= 120000
    assert "Chapters:" in sample and "detail12" in sample and "CHAPTER 12" in sample
    assert book_outline("Short book") == "Short book"


def test_gutenberg_fallback(monkeypatch):
    fetch = AsyncMock(side_effect=[ValueError("404"), ("Story", "https://www.gutenberg.org/files/11/11-0.txt")])
    monkeypatch.setattr(sources, "fetch_url", fetch)
    assert asyncio.run(sources.gutenberg("11"))[0] == "Story"
    assert fetch.await_count == 2


def test_topic_no_search_honest(monkeypatch):
    from service import PodcastRequest
    monkeypatch.setattr(sources, "search_topic", AsyncMock(return_value=[]))
    result = asyncio.run(sources.gather(PodcastRequest(kind="topic", title="Topic", query="vaccines")))
    assert result.no_live_sources and result.sources == []


def test_app_text_used_directly():
    from service import PodcastRequest
    result = asyncio.run(sources.gather(PodcastRequest(kind="chapter", title="Chapter", text="  Source body  ")))
    assert result.text == "Source body" and result.sources == [{"title": "Chapter"}]
