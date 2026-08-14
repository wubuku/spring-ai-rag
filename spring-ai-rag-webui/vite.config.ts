import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import type { Plugin } from 'vite'

function readDevPort(): number {
  const rawPort = process.env.VITE_DEV_PORT ?? '15173';
  if (!/^\d+$/.test(rawPort)) {
    throw new Error(`VITE_DEV_PORT must be an integer between 1 and 65535: ${rawPort}`);
  }
  const port = Number(rawPort);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`VITE_DEV_PORT must be an integer between 1 and 65535: ${rawPort}`);
  }
  return port;
}

// Strip /webui prefix in dev so Vite can serve the SPA correctly
function webuiDevMiddleware(): Plugin {
  return {
    name: 'webui-dev-middleware',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        if (req.url?.startsWith('/webui')) {
          const acceptsHtml = req.headers.accept?.includes('text/html');
          if (acceptsHtml && req.url !== '/webui/') {
            req.url = '/webui/';
          }
        }
        next();
      });
    },
  };
}

// https://vite.dev/config/
// base '/webui/' is set for production (assets at /webui/...).
// In dev, strip /webui prefix so Vite serves the SPA under /webui/.
export default defineConfig(() => {
  const port = readDevPort();
  const proxyTarget = process.env.VITE_DEV_PROXY_TARGET ?? 'http://127.0.0.1:8081';
  const origin = process.env.VITE_DEV_ORIGIN ?? `http://127.0.0.1:${port}/webui`;

  return {
    base: '/webui/',
    plugins: [react(), webuiDevMiddleware()],
    server: {
      host: '127.0.0.1',
      origin,
      port,
      strictPort: true,
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
  };
})
