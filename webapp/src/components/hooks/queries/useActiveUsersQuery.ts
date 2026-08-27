import type { ReactQueryOptions } from '@/lib/types';
import { useStream } from '@/lib/stream';

export function useActiveUsersQuery(websiteId: string, _options?: ReactQueryOptions) {
  const { data, error } = useStream<any>(
    websiteId ? `/websites/${websiteId}/active/stream` : null,
  );

  return { data, isLoading: !data && !error, error };
}
