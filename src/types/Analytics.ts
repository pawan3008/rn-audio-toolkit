export interface ListeningAnalytics {
  listeningTimeMs: number;
  completionPercent: number;
  skipped: boolean;
  speedUsed: number;
  sourceId: string;
  startedAt: number;
  endedAt?: number;
}

export interface Bookmark {
  id: string;
  positionMs: number;
  label?: string;
  createdAt: number;
}

export interface ABRepeatRange {
  startMs: number;
  endMs: number;
}
