import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { loadConfig } from '@/config';
import { createUserManager } from '@/auth/userManager';
import { AuthProvider } from '@/auth/AuthContext';
import { installAuth } from '@/api/http';
import { ApiError } from '@/lib/problem';
import { App } from '@/App';
import { AuthBridge } from '@/auth/AuthBridge';
import './styles.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
      refetchOnWindowFocus: false,
      staleTime: 5_000,
    },
    mutations: { retry: false },
  },
});

async function boot() {
  const root = createRoot(document.getElementById('root')!);
  try {
    const cfg = await loadConfig();
    const userManager = createUserManager(cfg);
    root.render(
      <StrictMode>
        <QueryClientProvider client={queryClient}>
          <AuthProvider userManager={userManager}>
            <AuthBridge install={installAuth} />
            <App />
          </AuthProvider>
        </QueryClientProvider>
      </StrictMode>,
    );
  } catch (e) {
    root.render(
      <main style={{ margin: '48px auto', maxWidth: 520, fontFamily: 'system-ui' }}>
        <h1>travel-os</h1>
        <p role="alert">
          The application configuration could not be loaded:{' '}
          {e instanceof Error ? e.message : String(e)}
        </p>
      </main>,
    );
  }
}

void boot();
