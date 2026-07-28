// Thin API client. Bearer token is held in memory (and mirrored to sessionStorage so a
// refresh doesn't drop the session). All calls go through /api, proxied to the backend.

let token: string | null = sessionStorage.getItem('nagad_token');

export function setToken(t: string | null) {
  token = t;
  if (t) sessionStorage.setItem('nagad_token', t);
  else sessionStorage.removeItem('nagad_token');
}

export function getToken() {
  return token;
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let msg = res.statusText;
    try {
      const j = await res.json();
      msg = j.message ?? msg;
    } catch {
      /* ignore */
    }
    throw new ApiError(res.status, msg);
  }
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return undefined as T;
  }
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export const api = {
  get: <T>(p: string) => req<T>('GET', p),
  post: <T>(p: string, body?: unknown) => req<T>('POST', p, body),
  del: <T>(p: string) => req<T>('DELETE', p),
  streamUrl: (p: string) => `/api${p}`,
};

/** Opens an SSE stream for a deploy run. Authorised by a single-use `streamTicket`
 *  (from POST /deploy) — never the bearer token, which must not appear in a URL. */
export function openDeployStream(
  streamTicket: string,
  handlers: {
    onLine?: (l: { level: string; text: string }) => void;
    onHost?: (h: { host: string; action: string; state: string }) => void;
    onComplete?: (c: { deploymentId: string; result: string; rows: ResultRow[] }) => void;
    onError?: (msg: string) => void;
  },
): () => void {
  const url = api.streamUrl('/deploy/stream') + `?ticket=${encodeURIComponent(streamTicket)}`;
  const es = new EventSource(url);
  es.addEventListener('line', (e) => handlers.onLine?.(JSON.parse((e as MessageEvent).data)));
  es.addEventListener('host', (e) => handlers.onHost?.(JSON.parse((e as MessageEvent).data)));
  es.addEventListener('complete', (e) => {
    handlers.onComplete?.(JSON.parse((e as MessageEvent).data));
    es.close();
  });
  es.addEventListener('error', (e) => {
    const data = (e as MessageEvent).data;
    handlers.onError?.(typeof data === 'string' && data ? data : 'stream error');
    es.close();
  });
  return () => es.close();
}

export interface ResultRow {
  host: string;
  app: string;
  before: string;
  after: string;
  verdict: string;
}
