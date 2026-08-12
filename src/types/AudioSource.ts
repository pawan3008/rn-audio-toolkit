export type AudioSourceType = 'url' | 'file' | 'base64' | 'asset' | 'blob';

export interface AudioSource {
  uri: string;
  type?: AudioSourceType;
  title?: string;
  artist?: string;
  album?: string;
  artwork?: string;
}

export type AudioSourceInput = string | AudioSource;
