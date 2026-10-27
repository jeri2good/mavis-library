import { tick } from '../lib/podsub.mjs';
export default async () => { await tick(); };
export const config = { schedule: '*/5 * * * *' };
