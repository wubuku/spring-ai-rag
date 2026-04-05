import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import type { Plugin } from 'vite'

// Strip /webui prefix in dev so Vite can serve the SPA correctly
function webuiDevMiddleware(): Plugin {
  return {
    name: 'webui-dev-middleware',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        if (req.url?.startsWith('/webui')) {
          req.url = req.url.replace(/^\/webui/, '') || '/';
        }
        next();
      });
    },
  };
}

// https://vite.dev/config/
// base '/webui/' is set for production (assets at /webui/...).
// In dev, strip /webui prefix so Vite serves SPA at http://localhost:5173/webui/
export default defineConfig({
  base: '/webui/',
  plugins: [react(), webuiDevMiddleware()],
  server: {
    origin: 'http://localhost:5173/webui',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
