import asyncio
import ipaddress
import re
import socket
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit

import aiohttp

UA = "Mozilla/5.0 (compatible; Feedbot/1.0)"
MAX_BYTES = 3 * 1024 * 1024


def validate_url(url):
    try:
        parsed = urlsplit(url)
        host = parsed.hostname
        if parsed.scheme != "https" or not host or parsed.username or parsed.password or parsed.port not in (None, 443):
            raise ValueError()
        host = host.rstrip(".").lower().encode("idna").decode("ascii")
        if (not re.fullmatch(r"[a-z0-9.-]+", host) or "." not in host or ".." in host
                or host.endswith((".local", ".internal", ".localhost", ".test")):
            raise ValueError()
        try:
            ipaddress.ip_address(host)
        except ValueError:
            pass
        else:
            raise ValueError()
        if re.fullmatch(r"(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+))*", host):
            raise ValueError()
        if not all(label and not label.startswith("-") and not label.endswith("-") for label in host.split(".")):
            raise ValueError()
        return host
    except (ValueError, UnicodeError) as exc:
        raise ValueError("Only public HTTPS hostnames are allowed") from exc


def public_addresses(addresses):
    if not addresses or any(not ipaddress.ip_address(ip).is_global for ip in addresses):
        raise ValueError("URL resolves to a non-public address")
    return addresses


class PublicResolver(aiohttp.abc.AbstractResolver):
    async def resolve(self, host, port=0, family=socket.AF_INET):
        results = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM, family=socket.AF_UNSPEC)
        addresses = [result[4][0] for result in results]
        public_addresses(addresses)
        return [{"hostname": host, "host": ip, "port": port, "family": socket.AF_INET6 if ":" in ip else socket.AF_INET, "proto": 0, "flags": 0} for ip in dict.fromkeys(addresses)]

    async def close(self):
        pass


async def fetch_url(url):
    connector = aiohttp.TCPConnector(resolver=PublicResolver(), use_dns_cache=False, force_close=True)
    async with aiohttp.ClientSession(connector=connector, timeout=aiohttp.ClientTimeout(total=15), trust_env=False, headers={"User-Agent": UA}) as session:
        for _ in range(4):
            validate_url(url)
            async with session.get(url, allow_redirects=False) as response:
                if response.status in (301, 302, 303, 307, 308):
                    location = response.headers.get("Location")
                    if not location:
                        raise ValueError("Redirect without location")
                    url = urljoin(url, location)
                    continue
                response.raise_for_status()
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
        super().__init__()
        self.depth = 0
        self.parts = []
        self.skip = 0
        self.title = ""
        self.in_title = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "title":
            self.in_title = True
        if tag in ("script", "style", "nav", "footer", "header", "aside", "form") or re.search(r"cookie|advert|sidebar|menu|navigation", attrs.get("class", "") + attrs.get("id", ""), re.I):
            self.skip += 1
        if tag in ("article", "main"):
            self.depth += 1
        if tag in ("p", "h1", "h2", "h3", "li", "br"):
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False
        if tag in ("article", "main"):
            self.depth = max(0, self.depth - 1)
        if tag in ("script", "style", "nav", "footer", "header", "aside", "form"):
            self.skip = max(0, self.skip - 1)

    def handle_data(self, data):
        if self.in_title:
            self.title += data
        if not self.skip:
            self.parts.append(data)


def extract_article(html):
    parser = MainText()
    parser.feed(html)
    text = re.sub(r"[ \t]+", " ", "".join(parser.parts))
    return parser.title.strip(), re.sub(r"\n\s*\n+", "\n", text).strip()


def strip_gutenberg(text):
    start = re.search(r"\*\*\* START OF (?:THE|THIS) PROJECT GUTENBERG EBOOK[^\n]*\*\*\*", text, re.I)
    end = re.search(r"\*\*\* END OF (?:THE|THIS) PROJECT GUTENBERG EBOOK", text, re.I)
    return text[start.end():end.start()].strip() if start and end else text.strip()


def book_outline(text):
    text = strip_gutenberg(text)
    headings = list(re.finditer(r"(?im)^\s*(?:chapter|book|part)\s+(?:[IVXLCDM]+|\d+|[a-z]+)(?:\b[^\n]{0,100})?$", text))
    if len(text) <= 120000:
        return text
    outline = ["Opening:\n" + text[:18000], "Chapters:\n" + "\n".join(text[m.start():m.end()].strip() for m in headings[:150])]
    budget = 100000 - sum(map(len, outline))
    for match in headings:
        if budget <= 0:
            break
        passage = text[match.start():match.start() + min(1800, max(300, budget // max(1, len(headings))))]
        outline.append(passage)
        budget -= len(passage)
    return "\n\n".join(outline)[:120000]


async def gutenberg(book_id):
    if not re.fullmatch(r"[1-9][0-9]{0,6}", book_id):
        raise ValueError("Invalid Gutenberg ID")
    urls = [f"https://www.gutenberg.org/cache/epub/{book_id}/pg{book_id}.txt", f"https://www.gutenberg.org/files/{book_id}/{book_id}-0.txt", f"https://www.gutenberg.org/files/{book_id}/{book_id}.txt"]
    for url in urls:
        try:
            text, final = await fetch_url(url)
            return strip_gutenberg(text), final
        except (aiohttp.ClientError, ValueError, TimeoutError):
            continue
    raise ValueError("Could not retrieve Gutenberg book")


async def search_topic(query):
    from ddgs import DDGS
    results = await asyncio.to_thread(lambda: list(DDGS().text(query, max_results=10)))
    gathered = []
    for result in results:
        if len(gathered) == 6:
            break
        url = result.get("href", "")
        try:
            validate_url(url)
            html, final = await fetch_url(url)
            title, text = extract_article(html)
            if len(text) > 250:
                gathered.append(({"title": title or result.get("title", final), "url": final}, text[:18000]))
        except (ValueError, aiohttp.ClientError, TimeoutError):
            continue
    return gathered
