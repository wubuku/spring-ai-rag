import { defineConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [tailwindcss()],
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: process.env.VITE_OPERATOR_API_PROXY || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
