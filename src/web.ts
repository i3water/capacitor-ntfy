import { WebPlugin } from '@capacitor/core';

import type {
  NtfyMessage,
  NtfyPermissionState,
  NtfyPlugin,
  NtfyPublishOptions,
  NtfyStartOptions,
  NtfyStatus,
} from './definitions';

export class NtfyWeb extends WebPlugin implements NtfyPlugin {
  private controller?: AbortController;
  private messages: NtfyMessage[] = [];
  private status: NtfyStatus = {
    state: 'stopped',
    running: false,
    connected: false,
    batteryOptimizationsIgnored: false,
    notificationPermission: this.permissionState(),
  };

  async start(options: NtfyStartOptions): Promise<NtfyStatus> {
    this.controller?.abort();
    this.controller = new AbortController();
    this.status = {
      ...this.status,
      state: 'connecting',
      running: true,
      connected: false,
      baseUrl: normalizeBaseUrl(options.baseUrl),
      topics: options.topics,
    };
    void this.readStream(options, this.controller.signal);
    await this.notifyListeners('statusChanged', this.status);
    return this.status;
  }

  async stop(): Promise<NtfyStatus> {
    this.controller?.abort();
    this.controller = undefined;
    this.status = { ...this.status, state: 'stopped', running: false, connected: false };
    await this.notifyListeners('statusChanged', this.status);
    return this.status;
  }

  async getStatus(): Promise<NtfyStatus> {
    return this.status;
  }

  async getMessages(options?: { limit?: number }): Promise<{ messages: NtfyMessage[] }> {
    const limit = Math.max(1, options?.limit ?? 100);
    return { messages: this.messages.slice(0, limit) };
  }

  async clearMessages(): Promise<void> {
    this.messages = [];
  }

  async consumeNotificationAction(): Promise<{ message?: NtfyMessage }> {
    return {}; // Native notification actions are not supported on Web.
  }

  async publish(options: NtfyPublishOptions): Promise<NtfyMessage> {
    const response = await fetch(`${normalizeBaseUrl(options.baseUrl)}/${encodeURIComponent(options.topic)}`, {
      method: 'POST',
      headers: publishHeaders(options),
      body: options.message,
    });
    if (!response.ok) {
      throw new Error(`ntfy publish failed: HTTP ${response.status}`);
    }
    return normalizeMessage(await response.json());
  }

  async requestNotificationPermission(): Promise<{ state: NtfyPermissionState }> {
    if (!('Notification' in window)) return { state: 'unsupported' };
    await Notification.requestPermission();
    this.status.notificationPermission = this.permissionState();
    return { state: this.status.notificationPermission };
  }

  async getNotificationPermission(): Promise<{ state: NtfyPermissionState }> {
    return { state: this.permissionState() };
  }

  async isIgnoringBatteryOptimizations(): Promise<{ value: boolean }> {
    return { value: false };
  }

  async openBatteryOptimizationSettings(): Promise<void> {
    throw this.unavailable('Battery optimization settings are only available on Android.');
  }

  async openAppSettings(): Promise<void> {
    throw this.unavailable('Application settings are only available on a native platform.');
  }

  private permissionState(): NtfyPermissionState {
    if (typeof window === 'undefined' || !('Notification' in window)) return 'unsupported';
    if (Notification.permission === 'granted') return 'granted';
    if (Notification.permission === 'denied') return 'denied';
    return 'prompt';
  }

  private async readStream(options: NtfyStartOptions, signal: AbortSignal): Promise<void> {
    const baseUrl = normalizeBaseUrl(options.baseUrl);
    const topics = options.topics.map(encodeURIComponent).join(',');
    const since = encodeURIComponent(options.initialSince ?? '10m');
    try {
      const response = await fetch(`${baseUrl}/${topics}/json?since=${since}`, {
        headers: authHeaders(options),
        signal,
      });
      if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
      this.status = { ...this.status, state: 'connected', connected: true, lastError: undefined };
      await this.notifyListeners('statusChanged', this.status);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let pending = '';
      while (!signal.aborted) {
        const { value, done } = await reader.read();
        if (done) break;
        pending += decoder.decode(value, { stream: true });
        const lines = pending.split('\n');
        pending = lines.pop() ?? '';
        for (const line of lines) {
          if (!line.trim()) continue;
          const raw = JSON.parse(line) as Record<string, unknown>;
          if (raw.event !== 'message') continue;
          const message = normalizeMessage(raw);
          this.messages = [message, ...this.messages.filter((item) => item.id !== message.id)].slice(0, 100);
          this.status.lastMessageId = message.id;
          await this.notifyListeners('messageReceived', message);
          if (options.showNotifications !== false && Notification.permission === 'granted') {
            new Notification(message.title ?? message.topic, { body: message.message });
          }
        }
      }
    } catch (error) {
      if (signal.aborted) return;
      this.status = {
        ...this.status,
        state: 'error',
        connected: false,
        lastError: error instanceof Error ? error.message : String(error),
      };
      await this.notifyListeners('statusChanged', this.status);
    }
  }
}

function normalizeBaseUrl(value: string): string {
  return value.trim().replace(/\/+$/, '');
}

function authHeaders(options: { token?: string; username?: string; password?: string }): HeadersInit {
  if (options.token) return { Authorization: `Bearer ${options.token}` };
  if (options.username) {
    return { Authorization: `Basic ${btoa(`${options.username}:${options.password ?? ''}`)}` };
  }
  return {};
}

function publishHeaders(options: NtfyPublishOptions): HeadersInit {
  const headers = new Headers(authHeaders(options));
  headers.set('Content-Type', 'text/plain; charset=utf-8');
  if (options.title) headers.set('Title', options.title);
  if (options.priority) headers.set('Priority', String(options.priority));
  if (options.tags?.length) headers.set('Tags', options.tags.join(','));
  if (options.click) headers.set('Click', options.click);
  return headers;
}

function normalizeMessage(raw: Record<string, unknown>): NtfyMessage {
  return {
    id: String(raw.id ?? ''),
    time: Number(raw.time ?? Math.floor(Date.now() / 1000)),
    event: String(raw.event ?? 'message'),
    topic: String(raw.topic ?? ''),
    message: typeof raw.message === 'string' ? raw.message : undefined,
    title: typeof raw.title === 'string' ? raw.title : undefined,
    priority: typeof raw.priority === 'number' ? raw.priority : undefined,
    tags: Array.isArray(raw.tags) ? raw.tags.map(String) : undefined,
    click: typeof raw.click === 'string' ? raw.click : undefined,
    expires: typeof raw.expires === 'number' ? raw.expires : undefined,
    raw,
  };
}
