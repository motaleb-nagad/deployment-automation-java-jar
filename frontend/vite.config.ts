import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies /api to the Spring Boot backend so the browser sees a
// single origin (no CORS in dev). In production the SPA is served behind the same
// gateway as the API.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
