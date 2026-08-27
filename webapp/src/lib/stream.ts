import { useEffect, useState } from 'react';
import { getApiUrl } from '@/lib/api-url';
import { getClientAuthToken } from '@/lib/client';
import { SHARE_CONTEXT_HEADER, SHARE_TOKEN_HEADER } from '@/lib/constants';
import { useApp } from '@/store/app';

/**
 * Subscribe to a server-sent stream instead of asking again on a timer.
 *
 * The server sends the whole current answer as the stream's first element and then only when
 * that answer changes, so a client that reconnects converges by taking the new stream's first
 * element rather than by replaying anything it missed.
 *
 * An event stream cannot carry request headers, so the sign-in token and any share token travel
 * in the query string. They are the same assertions the header form carries.
 */
export function useStream<T>(path: string | null): { data: T | null; error: Error | null } {
  const shareId = useApp(state => state.share?.shareId);
  const shareToken = useApp(state => state.shareToken?.token);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    if (!path) {
      return;
    }

    const params = new URLSearchParams();
    const token = getClientAuthToken();

    if (token) {
      params.set('token', token);
    }
    if (shareId && shareToken) {
      params.set(SHARE_TOKEN_HEADER, shareToken);
      params.set(SHARE_CONTEXT_HEADER, '1');
    }

    const query = params.toString();
    const url = `${getApiUrl(path)}${query ? `?${query}` : ''}`;
    const source = new EventSource(url);

    source.onmessage = message => {
      // A keep-alive arrives as an element with an empty payload. It carries no answer, so
      // it neither replaces the current one nor counts as a failure to read one.
      if (!message.data) {
        return;
      }
      try {
        setData(JSON.parse(message.data));
        setError(null);
      } catch (e) {
        setError(e as Error);
      }
    };

    source.onerror = () => {
      setError(new Error('stream interrupted'));
    };

    return () => source.close();
  }, [path, shareId, shareToken]);

  return { data, error };
}
