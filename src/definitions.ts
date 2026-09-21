import type { PluginListenerHandle } from '@capacitor/core';

export type NtfyConnectionState =
  'stopped' | 'starting' | 'connecting' | 'connected' | 'waitingForNetwork' | 'reconnecting' | 'error';

export type NtfyPermissionState = 'granted' | 'denied' | 'prompt' | 'unsupported';

export interface NtfyStartOptions {
  /** ntfy server URL, for example https://ntfy.sh. HTTPS is recommended. */
  baseUrl: string;
  /** One or more ntfy topic names. */
  topics: string[];
  /** ntfy access token. Takes precedence over username/password. */
  token?: string;
  username?: string;
  password?: string;
  /** Initial ntfy `since` value. Defaults to `10m`. */
  initialSince?: string;
  /** Restart the foreground service after a device reboot. Defaults to true. */
  autoStartOnBoot?: boolean;
  /** Show a local notification for each received ntfy message. Defaults to true. */
  showNotifications?: boolean;
  /** Maximum number of messages retained by the plugin. Defaults to 100. */
  historyLimit?: number;
  foregroundTitle?: string;
  foregroundText?: string;
  serviceChannelId?: string;
  serviceChannelName?: string;
  /** Base ID for five priority-specific message channels. Defaults to `capacitor_ntfy_messages`. */
  messageChannelId?: string;
  /** Base name shown for the five priority-specific message channels. Defaults to `ntfy 消息`. */
  messageChannelName?: string;
}

export interface NtfyStatus {
  state: NtfyConnectionState;
  running: boolean;
  connected: boolean;
  /** Android: Unix milliseconds of the native state/stream observation, not delivery proof. */
  observedAt?: number;
  /** Android: native stream activity that produced this snapshot. Cleared on reconnect/stop. */
  streamEvent?: 'open' | 'keepalive' | 'message';
  baseUrl?: string;
  topics?: string[];
  lastMessageId?: string;
  lastError?: string;
  retryInSeconds?: number;
  batteryOptimizationsIgnored: boolean;
  notificationPermission: NtfyPermissionState;
}

export interface NtfyMessage {
  id: string;
  time: number;
  event: string;
  topic: string;
  message?: string;
  title?: string;
  priority?: number;
  tags?: string[];
  click?: string;
  expires?: number;
  /** Original ntfy JSON payload. */
  raw: Record<string, unknown>;
}

export interface NtfyPublishOptions {
  baseUrl: string;
  topic: string;
  message: string;
  title?: string;
  priority?: number;
  tags?: string[];
  click?: string;
  token?: string;
  username?: string;
  password?: string;
}

export interface NtfyPlugin {
  start(options: NtfyStartOptions): Promise<NtfyStatus>;
  stop(): Promise<NtfyStatus>;
  getStatus(): Promise<NtfyStatus>;
  getMessages(options?: { limit?: number }): Promise<{ messages: NtfyMessage[] }>;
  clearMessages(): Promise<void>;
  /** Consume the latest native notification tap. No message after stop, eviction or configuration change.
   * Call after registering notificationAction, and on each event, including at cold start.
   * The app must authorize the current account and fetch current business details; never execute raw/click.
   */
  consumeNotificationAction(): Promise<{ message?: NtfyMessage }>;
  publish(options: NtfyPublishOptions): Promise<NtfyMessage>;
  requestNotificationPermission(): Promise<{ state: NtfyPermissionState }>;
  getNotificationPermission(): Promise<{ state: NtfyPermissionState }>;
  isIgnoringBatteryOptimizations(): Promise<{ value: boolean }>;
  openBatteryOptimizationSettings(): Promise<void>;
  openAppSettings(): Promise<void>;
  addListener(
    eventName: 'messageReceived',
    listenerFunc: (message: NtfyMessage) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'statusChanged', listenerFunc: (status: NtfyStatus) => void): Promise<PluginListenerHandle>;
  /** Signal only; consumeNotificationAction owns the single pending tap. Android only. */
  addListener(eventName: 'notificationAction', listenerFunc: () => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}
