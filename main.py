import logging

from service import create_app

logging.basicConfig(level=logging.INFO, format="%(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("notebooklm").setLevel(logging.ERROR)
app = create_app()
