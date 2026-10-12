import asyncio

import modal
import pytest

from cloud_budget import GPUBudget, GPUBudgetExceeded


def test_budget_passes_only_remaining_seconds_and_accumulates():
    clock = [0]
    budget = GPUBudget(10, lambda: clock[0])
    timeouts = []

    async def invoke(timeout):
        timeouts.append(timeout)
        clock[0] += 3
        return {'gpuSeconds': 2}

    asyncio.run(budget.run(invoke))
    asyncio.run(budget.run(invoke))
    assert timeouts == [10, 7]
    assert budget.seconds == 6
    assert budget.execution_seconds == 4


@pytest.mark.parametrize('exception', [TimeoutError, modal.exception.TimeoutError, modal.exception.FunctionTimeoutError])
def test_budget_timeouts_fail_without_fallback(exception):
    clock = [0]
    budget = GPUBudget(10, lambda: clock[0])

    async def invoke(timeout):
        clock[0] += 10
        raise exception('timeout')

    with pytest.raises(GPUBudgetExceeded):
        asyncio.run(budget.run(invoke))
    assert budget.seconds == 10
    with pytest.raises(GPUBudgetExceeded):
        asyncio.run(budget.run(invoke))


def test_failed_gpu_work_is_still_charged_to_budget():
    clock = [0]
    budget = GPUBudget(10, lambda: clock[0])

    async def invoke(timeout):
        clock[0] += 4
        raise RuntimeError('gpu failed')

    with pytest.raises(RuntimeError):
        asyncio.run(budget.run(invoke))
    assert budget.seconds == 4
