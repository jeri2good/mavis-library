import logging

from service import create_app

logging.basicConfig(level=logging.INFO, format="%(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
for name in ("notebooklm", "ddgs", "primp"):
    logging.getLogger(name).setLevel(logging.CRITICAL + 1)
app = create_app()
