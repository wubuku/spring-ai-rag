import http from 'node:http';
import { once } from 'node:events';

export interface ProxyRequest {
  method: string;
  url: string;
  lastEventId: string;
  idempotencyKey: string;
}

export interface InjectedFailure {
  status: number;
  failureClass: string;
  detail?: string;
  retryAfterSeconds?: number;
}

/**
 * 位于真实后端前面的本地反向代理。
 *
 * 它不做响应合成（除了显式注入的失败态）：静态资源和 API/SSE 都转发到真实
 * 服务端，因此浏览器仍然走真实 TCP、真实鉴权和真实模型。能力只用于测试无法
 * 用真实 provider 稳定制造的条件：流中途断网、指定状态码。
 */
export interface RealProxy {
  baseURL: string;
  requests: ProxyRequest[];
  infos: ProxyRequest[];
  failNext: (method: string, pathPrefix: string, failure: InjectedFailure) => void;
  failAlways: (method: string, pathPrefix: string, failure: InjectedFailure) => void;
  clearFailures: () => void;
  cutNextStreamOnce: () => void;
  close: () => Promise<void>;
}

export async function startRealProxy(upstreamBaseURL: string): Promise<RealProxy> {
  const upstream = new URL(upstreamBaseURL);
  const requests: ProxyRequest[] = [];
  const infos: ProxyRequest[] = [];
  let injected: { method: string; pathPrefix: string; failure: InjectedFailure } | null = null;
  let persistent: { method: string; pathPrefix: string; failure: InjectedFailure } | null = null;
  let cutArmed = false;
  let cutUsed = false;

  const server = http.createServer((req, res) => {
    void handle(req, res);
  });

  const logRequest = (req: http.IncomingMessage, body: string) => {
    const entry: ProxyRequest = {
      method: req.method ?? 'GET',
      url: req.url ?? '/',
      lastEventId: String(req.headers['last-event-id'] ?? ''),
      idempotencyKey: String(req.headers['idempotency-key'] ?? ''),
    };
    if (req.url?.includes('/api/')) requests.push(entry);
    else infos.push(entry);
    void body;
  };

  async function handle(req: http.IncomingMessage, res: http.ServerResponse) {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    const body = Buffer.concat(chunks);
    const url = req.url ?? '/';
    logRequest(req, body.toString('utf8'));

    const matched = persistent
      && req.method === persistent.method
      && url.startsWith(persistent.pathPrefix)
      ? persistent.failure
      : injected
        && req.method === injected.method
        && url.startsWith(injected.pathPrefix)
        ? injected.failure
        : null;
    if (matched) {
      if (!persistent) injected = null;
      const payload = JSON.stringify({
        code: `INJECTED_${matched.status}`,
        detail: matched.detail ?? `Injected failure ${matched.status}`,
        failureClass: matched.failureClass,
      });
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'X-RAG-Failure-Class': matched.failureClass,
      };
      if (matched.retryAfterSeconds !== undefined) {
        headers['Retry-After'] = String(matched.retryAfterSeconds);
      }
      res.writeHead(matched.status, headers);
      res.end(payload);
      return;
    }

    const target = new URL(url, upstream.origin);
    const headers: http.OutgoingHttpHeaders = { ...req.headers };
    headers.host = upstream.host;
    // 断流注入要按明文 SSE 帧判断；压缩后的字节流里没有可读的 `event:`。
    // 代理本身不需要压缩，去掉协商头让上游返回明文。
    delete headers['accept-encoding'];
    // 浏览器只对同源 POST 带 Origin；反向代理必须把它改写成上游源，
    // 否则真实服务端的同源校验会把经代理的 POST 判成跨站并返回 403。
    if (headers.origin) headers.origin = upstream.origin;
    const referer = headers.referer;
    if (typeof referer === 'string') {
      const localOrigin = `http://${req.headers.host ?? ''}`;
      if (referer.startsWith(localOrigin)) {
        headers.referer = `${upstream.origin}${referer.slice(localOrigin.length)}`;
      }
    }

    const upstreamRequest = http.request({
      protocol: target.protocol as 'http:',
      hostname: target.hostname,
      port: target.port,
      path: `${target.pathname}${target.search}`,
      method: req.method,
      headers,
    }, upstreamResponse => {
      const responseHeaders: http.OutgoingHttpHeaders = { ...upstreamResponse.headers };
      res.writeHead(upstreamResponse.statusCode ?? 502, responseHeaders);

      const isChatStream = req.method === 'POST' && url.includes('/chat/stream');
      if (isChatStream && cutArmed && !cutUsed) {
        let received = Buffer.alloc(0);
        upstreamResponse.on('data', chunk => {
          if (res.destroyed || cutUsed) return;
          received = Buffer.concat([received, chunk]);
          // 只转发到第一个 content 帧的结束位置，再把剩余字节丢掉后断流。
          // 不能"先整体转发再断"：上游若把 content/done 一起缓冲在同一个
          // chunk 里，浏览器会直接拿到 done 并正常结束，续传就永远不会发生。
          // latin1 保持"1 字符 = 1 字节"，索引可直接用于 Buffer 切片；
          // 同时兼容 `event:content` 与 `event: content` 两种写法。
          const text = received.toString('latin1');
          const match = /event:\s*content/.exec(text);
          if (!match) return;
          const contentAt = match.index;
          let boundary = text.indexOf('\n\n', contentAt);
          let boundaryLength = 2;
          const crlf = text.indexOf('\r\n\r\n', contentAt);
          if (crlf !== -1 && (boundary === -1 || crlf < boundary)) {
            boundary = crlf;
            boundaryLength = 4;
          }
          if (boundary === -1) return;
          const prefix = received.subarray(0, boundary + boundaryLength);
          cutUsed = true;
          // 用 end 优雅半关：先冲刷这一帧再发 FIN。直接 destroy 会发 RST，
          // 浏览器可能整段丢弃还没读取的字节，客户端就既没拿到 id 也没拿到
          // content，只能当作"连接从一开始就失败"。
          res.end(prefix, () => {
            upstreamResponse.destroy();
          });
        });
        upstreamResponse.on('end', () => {
          if (!cutUsed) res.end();
        });
        return;
      }
      upstreamResponse.pipe(res);
    });

    upstreamRequest.on('error', error => {
      if (!res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'text/plain' });
      }
      res.end(`upstream error: ${error.message}`);
    });
    req.on('aborted', () => upstreamRequest.destroy());
    upstreamRequest.end(body);
  }

  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  if (!address || typeof address === 'string') {
    throw new Error('real proxy did not expose a TCP port');
  }

  return {
    baseURL: `http://127.0.0.1:${address.port}`,
    requests,
    infos,
    failNext(method, pathPrefix, failure) {
      injected = { method, pathPrefix, failure };
    },
    failAlways(method, pathPrefix, failure) {
      persistent = { method, pathPrefix, failure };
    },
    clearFailures() {
      injected = null;
      persistent = null;
    },
    cutNextStreamOnce() {
      cutArmed = true;
      cutUsed = false;
    },
    async close() {
      server.closeAllConnections();
      await new Promise<void>(resolve => server.close(() => resolve()));
    },
  };
}
