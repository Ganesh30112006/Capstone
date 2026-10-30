import { useEffect, useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/api/httpClient';
import { TooltipProvider } from '@/components/ui/tooltip';
import { Toaster } from '@/components/ui/toaster';
import { useToast } from '@/hooks/use-toast';

/**
 * Retry policy, differentiated by what a failure actually means.
 *
 * A blanket `retry: 1` costs roughly 2N requests during a full outage, which
 * is the moment the gateway can least afford them. But dropping to zero
 * everywhere makes ordinary transient blips look like outages. So the policy
 * splits three ways:
 *
 *  - a transient read (a 5xx, a timeout, an unreachable gateway) is worth one
 *    retry, since it often succeeds;
 *  - a definitive answer — 401, 403, 404, 422, 429 — is retried never. The
 *    server already told us; asking again changes nothing and, for 429,
 *    actively makes it worse;
 *  - mutations are never retried automatically, because none of the write
 *    endpoints here advertise idempotency. Re-sending a device command or a
 *    firmware push on a timeout could dispatch it twice.
 */
const DEFINITIVE_STATUSES = new Set([400, 401, 403, 404, 409, 422, 429]);

function retryReads(failureCount: number, error: unknown): boolean {
  const status = error instanceof ApiError ? error.status : undefined;
  if (status !== undefined && DEFINITIVE_STATUSES.has(status)) return false;
  return failureCount < 1;
}

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: retryReads,
        staleTime: 30_000,
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: false,
      },
    },
  });
}

/** Surfaces gateway rate limiting as a toast instead of a blocking alert(). */
function RateLimitNotice() {
  const { toast } = useToast();
  useEffect(() => {
    const onRateLimit = () =>
      toast({ title: 'Too many requests', description: 'The gateway is rate limiting this session. Try again shortly.', variant: 'destructive' });
    window.addEventListener('api:rate-limit', onRateLimit);
    return () => window.removeEventListener('api:rate-limit', onRateLimit);
  }, [toast]);
  return null;
}

export function AppProviders({ children }: { children: ReactNode }) {
  // One cache per mounted app, so a signed-out session never inherits the
  // previous operator's cached identity or fleet data.
  const [queryClient] = useState(createQueryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RateLimitNotice />
        {children}
        <Toaster />
      </TooltipProvider>
    </QueryClientProvider>
  );
}
