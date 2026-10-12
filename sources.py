import asyncio
import ipaddress
import re
import socket
from dataclasses import dataclass
from html.parser import HTMLParser
from urllib.parse import parse_qs, quote, urljoin, urlsplit

import aiohttp

UA = "Mozilla/5.0 (compatible; Feedbot/1.0)"
MAX_BYTES = 3 * 1024 * 1024


def validate_url(url):
    try:
        if not isinstance(url, str) or re.search(r"[\s\\\x00-\x1f\x7f]", url):
            raise ValueError()
        parsed = urlsplit(url)
        host = parsed.hostname
        if parsed.scheme != "https" or not host or parsed.username is not None or parsed.password is not None or parsed.port not in (None, 443):
            raise ValueError()
        host = host.rstrip(".").lower().encode("idna").decode("ascii")
        if (len(host) > 253 or not re.fullmatch(r"[a-z0-9.-]+", host) or "." not in host or ".." in host
                or host.endswith((".local", ".internal", ".localhost", ".test", ".home", ".lan"))):
            raise ValueError()
        try:
            ipaddress.ip_address(host)
        except ValueError:
            pass
        else:
            raise ValueError()
        if re.fullmatch(r"(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+))*", host):
            raise ValueError()
        if not all(len(label) <= 63 and not label.startswith("-") and not label.endswith("-") for label in host.split(".")):
            raise ValueError()
        return host
    except (ValueError, UnicodeError) as exc:
        raise ValueError("Only public HTTPS hostnames are allowed") from exc


def public_addresses(addresses):
    if not addresses:
        raise ValueError("URL resolves to a non-public address")
    for address in addresses:
        ip = ipaddress.ip_address(address)
        if isinstance(ip, ipaddress.IPv6Address):
            if ip.teredo:
                raise ValueError("URL resolves to a non-public address")
            if ip in ipaddress.ip_network("64:ff9b::/96"):
                ip = ipaddress.IPv4Address(int(ip) & 0xffffffff)
            else:
                ip = ip.ipv4_mapped or ip.sixtofour or ip
        if not ip.is_global or ip.is_multicast or ip.is_reserved:
            raise ValueError("URL resolves to a non-public address")
    return addresses


class PublicResolver(aiohttp.abc.AbstractResolver):
    async def resolve(self, host, port=0, family=socket.AF_INET):
        validate_url("https://" + host)
        results = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM, family=socket.AF_UNSPEC)
        addresses = public_addresses([result[4][0] for result in results])
        return [{"hostname": host, "host": ip, "port": port, "family": socket.AF_INET6 if ":" in ip else socket.AF_INET, "proto": 0, "flags": 0} for ip in dict.fromkeys(addresses)]

    async def close(self):
        pass


async def fetch_url(url):
    connector = aiohttp.TCPConnector(resolver=PublicResolver(), use_dns_cache=False, force_close=True)
    async with asyncio.timeout(15), aiohttp.ClientSession(connector=connector, cookie_jar=aiohttp.DummyCookieJar(), timeout=aiohttp.ClientTimeout(total=15), trust_env=False, auto_decompress=False, headers={"User-Agent": UA, "Accept-Encoding": "identity"}) as session:
        for redirects in range(4):
            validate_url(url)
            async with session.get(url, allow_redirects=False) as response:
                if response.status in (301, 302, 303, 307, 308):
                    location = response.headers.get("Location")
                    if not location:
                        raise ValueError("Redirect without location")
                    url = urljoin(url, location)
                    validate_url(url)
                    if redirects == 3:
                        raise ValueError("Too many redirects")
                    continue
                response.raise_for_status()
                if response.headers.get("Content-Encoding", "identity").lower() != "identity":
                    raise ValueError("Source uses unsupported content encoding")
                if response.content_length and response.content_length > MAX_BYTES:
                    raise ValueError("Source exceeds 3 MB")
                chunks = bytearray()
                async for chunk in response.content.iter_chunked(65536):
                    chunks.extend(chunk)
                    if len(chunks) > MAX_BYTES:
                        raise ValueError("Source exceeds 3 MB")
                return chunks.decode(response.charset or "utf-8", errors="replace"), url
    raise ValueError("Too many redirects")


class MainText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.parts = []
        self.main_parts = []
        self.title = ""

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        skipped = tag in ("script", "style", "nav", "footer", "header", "aside", "form", "noscript", "svg") or bool(re.search(r"cookie|advert|sidebar|menu|navigation", attrs.get("class", "") + " " + attrs.get("id", ""), re.I))
        if tag not in ("br", "img", "hr", "meta", "link", "input", "source", "wbr", "area", "embed"):
            self.stack.append((tag, skipped))
        if tag in ("p", "h1", "h2", "h3", "li", "br", "div"):
            self.handle_data("\n")

    def handle_startendtag(self, tag, attrs):
        if tag == "br":
            self.handle_data("\n")

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]
                break
        if tag in ("p", "li", "h1", "h2", "h3", "div"):
            self.handle_data("\n")

    def handle_data(self, data):
        if any(tag == "title" for tag, _ in self.stack):
            self.title += data
            return
        if not any(skip for _, skip in self.stack):
            self.parts.append(data)
            if any(tag in ("main", "article") for tag, _ in self.stack):
                self.main_parts.append(data)


def extract_article(html):
    if not re.search(r"<(?:html|body|p|article|main)\b", html, re.I):
        return "", html.strip()
    parser = MainText()
    parser.feed(html)
    text = re.sub(r"[ \t]+", " ", "".join(parser.main_parts or parser.parts))
    return parser.title.strip(), re.sub(r"\n\s*\n+", "\n", text).strip()


def strip_gutenberg(text):
    start = re.search(r"\*\*\* START OF (?:THE|THIS) PROJECT GUTENBERG EBOOK[^\n]*\*\*\*", text, re.I)
    end = re.search(r"\*\*\* END OF (?:THE|THIS) PROJECT GUTENBERG EBOOK", text, re.I)
    return text[start.end() if start else 0:end.start() if end else len(text)].strip()


def chapter_chunks(text):
    headings = list(re.finditer(r"(?im)^[ \t]*(?:chapter|book|part)[ \t]+(?:[IVXLCDM]+|\d+|[a-z]+)\b[^\n]{0,100}$", text))
    return [(m.group().strip(), text[m.start():headings[i + 1].start() if i + 1 < len(headings) else len(text)]) for i, m in enumerate(headings)]


def book_outline(text):
    text = strip_gutenberg(text)
    chapters = chapter_chunks(text)
    if len(text) <= 120000:
        return text
    outline = ["Opening:\n" + text[:18000], "Chapters:\n" + "\n".join(title for title, _ in chapters[:150])]
    if chapters:
        budget = max(0, 115000 - sum(map(len, outline)))
        per_chapter = max(100, budget // len(chapters))
        for title, chapter in chapters:
            width = min(1800, per_chapter // 2)
            outline.append(title + "\n" + chapter[:width] + "\n[Later passage]\n" + chapter[max(width, len(chapter) // 2):max(width, len(chapter) // 2) + width])
    else:
        outline.extend("Passage:\n" + text[i:i + 6000] for i in range(18000, len(text), max(6000, len(text) // 14)))
    return "\n\n".join(outline)[:120000]


async def gutenberg(book_id):
    if not re.fullmatch(r"[1-9][0-9]{0,6}", book_id):
        raise ValueError("Invalid Gutenberg ID")
    digits = "/".join(book_id[:-1]) if len(book_id) > 1 else "0"
    mirror_path = digits + "/" + book_id
    urls = [f"https://www.gutenberg.org/cache/epub/{book_id}/pg{book_id}.txt", f"https://www.gutenberg.org/files/{book_id}/{book_id}-0.txt", f"https://www.gutenberg.org/files/{book_id}/{book_id}.txt", f"https://gutenberg.pglaf.org/{mirror_path}/{book_id}-0.txt", f"https://gutenberg.mirrorservice.org/{mirror_path}/{book_id}-0.txt"]
    for url in urls:
        try:
            text, final = await fetch_url(url)
            if "<html" in text[:1000].lower():
                continue
            return strip_gutenberg(text), final
        except (aiohttp.ClientError, ValueError, TimeoutError):
            continue
    raise ValueError("Could not retrieve Gutenberg book")


class SearchLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.urls = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "a" and "result__a" in attrs.get("class", ""):
            url = attrs.get("href", "")
            url = parse_qs(urlsplit(url).query).get("uddg", [url])[0]
            self.urls.append({"href": url, "title": ""})


async def search_topic(query):
    try:
        from ddgs import DDGS
        results = await asyncio.wait_for(asyncio.to_thread(lambda: list(DDGS(timeout=10).text(query, max_results=12))), timeout=20)
    except Exception:
        results = []
    if not results:
        try:
            html, _ = await fetch_url("https://html.duckduckgo.com/html/?q=" + quote(query))
            parser = SearchLinks()
            parser.feed(html)
            results = parser.urls
        except (ValueError, aiohttp.ClientError, TimeoutError):
            return []
    gathered, seen = [], set()
    for result in results:
        if len(gathered) == 6:
            break
        url = result.get("href", "")
        try:
            validate_url(url)
            if url in seen:
                continue
            seen.add(url)
            html, final = await fetch_url(url)
            title, text = extract_article(html)
            if len(text) > 250:
                gathered.append(({"title": title or result.get("title") or final, "url": final}, text[:18000]))
        except (ValueError, aiohttp.ClientError, TimeoutError):
            continue
    return gathered


@dataclass
class Gathered:
    text: str
    sources: list
    no_live_sources: bool = False


async def gather(request):
    if request.kind == "topic":
        found = await search_topic(request.query)
        return Gathered("\n\n".join(f"SOURCE: {source['title']}\n{text}" for source, text in found) or "Topic: " + request.query, [source for source, _ in found], not found)
    if request.text and request.kind != "article":
        text, sources = request.text, [{"title": request.title}]
    elif request.gutenbergId and request.kind == "book":
        text, url = await gutenberg(request.gutenbergId)
        sources = [{"title": request.title, "url": url}]
    else:
        html, url = await fetch_url(request.url)
        title, text = extract_article(html)
        sources = [{"title": title or request.title, "url": url}]
    if not text.strip():
        raise ValueError("Source contains no readable text")
    return Gathered(book_outline(text) if request.kind == "book" else text[:400000], sources)
