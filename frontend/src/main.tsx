import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './theme/tokens.css';
import { App } from './App';

// Fleet data is collector-backed and not live; keep it briefly cached and refetch on demand.
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, staleTime: 30_000, refetchOnWindowFocus: false } },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
);
