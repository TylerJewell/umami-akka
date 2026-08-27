/**
 * The shapes the rebuild answers with.
 *
 * These used to be declared beside the queries that built them. The queries are the rebuild's
 * now, so the shapes are declared here and the components that read them are otherwise
 * unchanged.
 */

export type HeatmapMode = 'click' | 'scroll';

export interface HeatmapPoint {
  x: number;
  y: number;
  pageX: number;
  pageY: number;
  pageW: number;
  pageH: number;
  viewportW: number;
  viewportH: number;
  count: number;
}

export interface HeatmapScrollBucket {
  depth: number;
  sessions: number;
  pageW: number;
  pageH: number;
  viewportW: number;
  viewportH: number;
}

export interface HeatmapSnapshot {
  kind: 'iframe';
  id: string;
  url: string;
  pageW: number;
  pageH: number;
  viewportW: number;
  viewportH: number;
}

export interface HeatmapResult {
  mode: HeatmapMode;
  pages: { urlPath: string; count: number; sessions: number }[];
  points: HeatmapPoint[];
  scroll: { buckets?: HeatmapScrollBucket[]; totalSessions?: number };
  snapshot: HeatmapSnapshot | null;
}

export interface FunnelResult {
  type: string;
  value: string;
  visitors: number;
  previous: number;
  dropped: number;
  dropoff: number;
  remaining: number;
}

export interface WebsiteListChartData {
  [websiteId: string]: { values: number[]; total: number };
}
