import asyncio
import time

import modal


class GPUBudgetExceeded(RuntimeError):
    def __init__(self):
        super().__init__('GPU budget exceeded (900 seconds); generation stopped')


class GPUBudget:
    def __init__(self, limit=900, clock=time.monotonic):
        self.limit = limit
        self.clock = clock
        self.seconds = 0.0
        self.execution_seconds = 0.0

    async def run(self, invoke, *args):
        remaining = self.limit - self.seconds
        if remaining <= 0:
            raise GPUBudgetExceeded()
        started = self.clock()
        try:
            result = await invoke(*args, timeout=remaining)
        except (TimeoutError, modal.exception.TimeoutError):
            raise GPUBudgetExceeded() from None
        finally:
            self.seconds += self.clock() - started
        self.execution_seconds += result['gpuSeconds']
        if self.seconds >= self.limit:
            raise GPUBudgetExceeded()
        return result
