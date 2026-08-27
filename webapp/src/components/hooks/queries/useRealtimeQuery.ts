import type { RealtimeData } from '@/lib/types';
import { useStream } from '@/lib/stream';

export function useRealtimeQuery(websiteId: string) {
  const { data, error } = useStream<RealtimeData>(
    websiteId ? `/realtime/${websiteId}/stream` : null,
  );

  return { data, isLoading: !data && !error, error };
}
