// GET /api/features — which optional services the owner has switched on.
// Reports names only; never keys.

import { json, onlyGet, env, requireOwner } from '../lib/shared.mjs';
import { ttsProvider } from './tts.mjs';
import { llmConfig } from './assistant.mjs';

export default async (req) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  const llm = llmConfig();
  const checking = req.headers.has('x-mavis-access');
  const owner = checking ? !(await requireOwner(req)) : undefined;
  return json({
    owner,
    accessCode: Boolean(env('MAVIS_ACCESS_CODE')),
    cloudVoice: ttsProvider(),
    assistant: llm ? { provider: llm.provider, model: llm.model } : null,
    jev: Boolean(env('EDENAI_API_KEY')),
    googleBooksKey: Boolean(env('GOOGLE_BOOKS_API_KEY')),
  }, { cache: checking ? 'no-store' : 'public, max-age=60' });
};

export const config = { path: '/api/features' };
