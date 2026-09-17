import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Same-origin in every environment: the browser only ever calls /api/v1/... on its own origin.
// In development Vite proxies those paths to the services running on the host (or the Docker
// stack, which publishes the same ports); in production nginx does (docker/web/nginx.conf).
const routes: Record<string, number> = {
  '/api/v1/trips': 8081,
  '/api/v1/policies': 8082,
  '/api/v1/policy-decisions': 8082,
  '/api/v1/orders': 8085,
  '/api/v1/audit': 8088,
  '/api/v1/disruptions': 8089,
  '/api/v1/demand': 8090,
  '/api/v1/connectors': 8090,
  '/api/v1/learning': 8091,
};

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    strictPort: true,
    proxy: Object.fromEntries(
      Object.entries(routes).map(([path, port]) => [
        path,
        { target: `http://localhost:${port}`, changeOrigin: false },
      ]),
    ),
  },
  build: {
    sourcemap: false,
    target: 'es2022',
    rollupOptions: {
      // the silent-renew page is a second entry: a tiny document loaded in a hidden iframe
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        renew: fileURLToPath(new URL('./silent-renew.html', import.meta.url)),
      },
    },
  },
});
