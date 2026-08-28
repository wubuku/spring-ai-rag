#!/usr/bin/env node

import http from 'node:http';

const port = Number(process.env.ALERT_NOTIFICATION_STUB_PORT ?? process.argv[2] ?? 4281);
const requests = [];
let blockedRelease;

function json(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
}

async function readBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 1_048_576) {
      throw new Error('request body too large');
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://127.0.0.1:${port}`);
  if (request.method === 'GET' && url.pathname === '/health') {
    json(response, 200, { status: 'UP' });
    return;
  }
  if (request.method === 'GET' && url.pathname === '/requests') {
    json(response, 200, { requests });
    return;
  }
  if (request.method === 'POST' && url.pathname === '/control/release') {
    blockedRelease?.();
    blockedRelease = undefined;
    json(response, 200, { released: true });
    return;
  }
  if (request.method !== 'POST' || !url.pathname.startsWith('/robot/')) {
    json(response, 404, { error: 'not_found' });
    return;
  }

  try {
    const body = await readBody(request);
    const pathCount = requests.filter(item => item.path === url.pathname).length + 1;
    requests.push({
      sequence: requests.length + 1,
      pathSequence: pathCount,
      path: url.pathname,
      receivedAt: new Date().toISOString(),
      body,
    });

    if (url.pathname === '/robot/transient' && pathCount === 1) {
      json(response, 503, { errcode: 503, errmsg: 'transient fixture' });
      return;
    }
    if (url.pathname === '/robot/block' && pathCount === 1) {
      await new Promise(resolve => {
        blockedRelease = resolve;
        request.once('aborted', resolve);
        response.once('close', resolve);
      });
      if (!response.writableEnded && !response.destroyed) {
        json(response, 200, { errcode: 0, errmsg: 'released' });
      }
      return;
    }
    json(response, 200, { errcode: 0, errmsg: 'ok' });
  } catch (error) {
    if (!response.headersSent) {
      json(response, 400, { error: error.message });
    } else {
      response.destroy();
    }
  }
});

server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`alert notification stub listening on ${port}\n`);
});

function shutdown() {
  blockedRelease?.();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1000).unref();
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
