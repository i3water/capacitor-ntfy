# capacitor-ntfy

Android-first Capacitor 8 plugin for receiving messages from a self-hosted or public ntfy server through a native foreground service. The connection does not depend on WebView timers; received messages are retained locally and can produce native Android notifications. Android or vendor process restrictions can still suspend or stop delivery.

This project includes a complete demo application in [`demo/`](./demo/).

## Platform support

| Platform | Support |
| --- | --- |
| Android 7+ | Native foreground service, JSON stream, notifications, reboot recovery |
| Web | Foreground-only streaming implementation for development |
| iOS | Intentional stub; use APNs for production iOS push delivery |

The Android implementation does not use Firebase or a handset vendor push SDK. It therefore needs a visible foreground-service notification and cannot receive messages after the user force-stops the application.

## Install

```bash
npm install capacitor-ntfy
npx cap sync android
```

The plugin manifest contributes the Internet, notification, boot, and foreground-service permissions. Android 13+ notification permission still has to be requested at runtime.

## Quick start

```ts
import { Ntfy } from 'capacitor-ntfy';

await Ntfy.requestNotificationPermission();

await Ntfy.start({
  baseUrl: 'https://ntfy.example.com',
  topics: ['orders-alice'],
  token: 'tk_example',
  autoStartOnBoot: true,
  showNotifications: true,
});

await Ntfy.addListener('messageReceived', (message) => {
  console.log('ntfy message', message);
});

await Ntfy.addListener('statusChanged', (status) => {
  console.log('ntfy state', status.state);
});
```

The plugin stores the active configuration so Android can restart the service. Tokens and passwords are encrypted with an AES-GCM key held by Android Keystore. Message history is stored in private application preferences and is capped by `historyLimit`.

## Android notification taps

Register `notificationAction`, then call `consumeNotificationAction()` once at startup and on each event. The event is only a wake-up signal; consumption returns `{ message }` for the latest tap, or `{}`. Only one pending tap is retained while the WebView starts, and each tap is consumed once. Stop, clear, history eviction and configuration changes invalidate old references. Configuration changes also clear subscription-scoped history.

The immutable PendingIntent always opens the host app. It carries an opaque reference to local history, never credentials, a business command or an executable `click` URL. The plugin does not interpret account identities or sharing grants. The app must check its current account/subscription, validate the business message identifier, fetch authenticated current details, and ask the user to confirm any action. Received-message deduplication must not suppress a subsequent tap on the same message.

Android warm taps and a real notification reopening the finished host Activity have been verified. This is not a process-death delivery guarantee. On one OPPO Android 16 test phone, the application UID was frozen despite an active foreground service; its native stream stopped until foreground interaction. Web returns an empty result; iOS remains unsupported.

`NtfyStatus.observedAt` is the Android native observation time in Unix milliseconds. `streamEvent` identifies an actual `open`, `keepalive` or `message`; reconnect snapshots omit it. HTTP headers alone no longer report a connected stream. Heartbeats update the stored snapshot without reposting notifications or generating repeated JS status events. These are observations, not a delivery receipt or a guarantee of current activity: a frozen process cannot refresh them. The timestamp is diagnostic only, never an authorization clock.

## Sending a message

Use your backend in production. The `publish` method is included for diagnostics and the demo:

```ts
await Ntfy.publish({
  baseUrl: 'https://ntfy.example.com',
  topic: 'orders-alice',
  title: 'New order',
  message: 'Order #1042 is ready.',
  token: 'tk_example',
});
```

Do not place a privileged server token in client code. Give each device narrowly scoped ntfy credentials or publish through your authenticated backend.

## Reliability notes

- Ask the user to set the app battery mode to unrestricted if instant delivery is a core feature. `openBatteryOptimizationSettings()` opens the standard Android list.
- Some Android variants also require the user to enable auto-start. This is outside the standard Android API and is not automated by this plugin.
- `START_STICKY` and a boot receiver help recover the service, but user force-stop always wins.
- Do not fight process restrictions with hidden restarts, fake foreground work, or WebView keepalive timers. Native foreground service presence alone does not prove that threads can run. Backend acceptance is not a phone receipt; retain authoritative business messages separately so foreground refresh can recover missed reminders.
- The last received ntfy message ID is reused as `since` after reconnect, and message IDs are deduplicated locally.
- Use HTTPS. Plain HTTP may require a host application network-security configuration and is unsuitable for credentials.
- Android 14+ declares the foreground service as `specialUse`. App-store policy review may require explaining why continuous, user-visible notification delivery is the app's core function.

## Android customization

The foreground service uses one low-importance channel. Messages follow ntfy's priority model and use five channels derived from `messageChannelId`: `_min`, `_low`, `_default`, `_high`, and `_urgent`. This lets users configure sound, vibration, pop-over behavior, and Do Not Disturb handling separately for every priority.

Notification channel IDs are created the first time the service starts and Android keeps user channel choices. Changing a channel's importance programmatically after creation has no effect; use a new base channel ID when required. The unsuffixed message channel from earlier development builds is no longer used.

The plugin uses the host application's launcher icon as its default notification small icon. A production application should replace this with a dedicated monochrome notification asset in a fork or expose an application-specific resource.

## Development

```bash
npm install
npm run build
npm run verify:android

cd demo
npm install
npm run build
npx cap sync android
npx cap open android
```

The Android plugin namespace is `io.github.coloz.capacitor.ntfy`, and the Demo application ID is `io.github.coloz.capacitor.ntfy.demo`.

## API

<docgen-index>

* [`start(...)`](#start)
* [`stop()`](#stop)
* [`getStatus()`](#getstatus)
* [`getMessages(...)`](#getmessages)
* [`clearMessages()`](#clearmessages)
* [`consumeNotificationAction()`](#consumenotificationaction)
* [`publish(...)`](#publish)
* [`requestNotificationPermission()`](#requestnotificationpermission)
* [`getNotificationPermission()`](#getnotificationpermission)
* [`isIgnoringBatteryOptimizations()`](#isignoringbatteryoptimizations)
* [`openBatteryOptimizationSettings()`](#openbatteryoptimizationsettings)
* [`openAppSettings()`](#openappsettings)
* [`addListener('messageReceived', ...)`](#addlistenermessagereceived-)
* [`addListener('statusChanged', ...)`](#addlistenerstatuschanged-)
* [`addListener('notificationAction', ...)`](#addlistenernotificationaction-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### start(...)

```typescript
start(options: NtfyStartOptions) => Promise<NtfyStatus>
```

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#ntfystartoptions">NtfyStartOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#ntfystatus">NtfyStatus</a>&gt;</code>

--------------------


### stop()

```typescript
stop() => Promise<NtfyStatus>
```

**Returns:** <code>Promise&lt;<a href="#ntfystatus">NtfyStatus</a>&gt;</code>

--------------------


### getStatus()

```typescript
getStatus() => Promise<NtfyStatus>
```

**Returns:** <code>Promise&lt;<a href="#ntfystatus">NtfyStatus</a>&gt;</code>

--------------------


### getMessages(...)

```typescript
getMessages(options?: { limit?: number | undefined; } | undefined) => Promise<{ messages: NtfyMessage[]; }>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ limit?: number; }</code> |

**Returns:** <code>Promise&lt;{ messages: NtfyMessage[]; }&gt;</code>

--------------------


### clearMessages()

```typescript
clearMessages() => Promise<void>
```

--------------------


### consumeNotificationAction()

```typescript
consumeNotificationAction() => Promise<{ message?: NtfyMessage; }>
```

Consume the latest native notification tap. No message after stop, eviction or configuration change.
Call after registering notificationAction, and on each event, including at cold start.
The app must authorize the current account and fetch current business details; never execute raw/click.

**Returns:** <code>Promise&lt;{ message?: <a href="#ntfymessage">NtfyMessage</a>; }&gt;</code>

--------------------


### publish(...)

```typescript
publish(options: NtfyPublishOptions) => Promise<NtfyMessage>
```

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code><a href="#ntfypublishoptions">NtfyPublishOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#ntfymessage">NtfyMessage</a>&gt;</code>

--------------------


### requestNotificationPermission()

```typescript
requestNotificationPermission() => Promise<{ state: NtfyPermissionState; }>
```

**Returns:** <code>Promise&lt;{ state: <a href="#ntfypermissionstate">NtfyPermissionState</a>; }&gt;</code>

--------------------


### getNotificationPermission()

```typescript
getNotificationPermission() => Promise<{ state: NtfyPermissionState; }>
```

**Returns:** <code>Promise&lt;{ state: <a href="#ntfypermissionstate">NtfyPermissionState</a>; }&gt;</code>

--------------------


### isIgnoringBatteryOptimizations()

```typescript
isIgnoringBatteryOptimizations() => Promise<{ value: boolean; }>
```

**Returns:** <code>Promise&lt;{ value: boolean; }&gt;</code>

--------------------


### openBatteryOptimizationSettings()

```typescript
openBatteryOptimizationSettings() => Promise<void>
```

--------------------


### openAppSettings()

```typescript
openAppSettings() => Promise<void>
```

--------------------


### addListener('messageReceived', ...)

```typescript
addListener(eventName: 'messageReceived', listenerFunc: (message: NtfyMessage) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                      |
| ------------------ | ------------------------------------------------------------------------- |
| **`eventName`**    | <code>'messageReceived'</code>                                            |
| **`listenerFunc`** | <code>(message: <a href="#ntfymessage">NtfyMessage</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('statusChanged', ...)

```typescript
addListener(eventName: 'statusChanged', listenerFunc: (status: NtfyStatus) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                   |
| ------------------ | ---------------------------------------------------------------------- |
| **`eventName`**    | <code>'statusChanged'</code>                                           |
| **`listenerFunc`** | <code>(status: <a href="#ntfystatus">NtfyStatus</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('notificationAction', ...)

```typescript
addListener(eventName: 'notificationAction', listenerFunc: () => void) => Promise<PluginListenerHandle>
```

Signal only; consumeNotificationAction owns the single pending tap. Android only.

| Param              | Type                              |
| ------------------ | --------------------------------- |
| **`eventName`**    | <code>'notificationAction'</code> |
| **`listenerFunc`** | <code>() =&gt; void</code>        |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

--------------------


### Interfaces


#### NtfyStatus

| Prop                              | Type                                                                | Description                                                                             |
| --------------------------------- | ------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| **`state`**                       | <code><a href="#ntfyconnectionstate">NtfyConnectionState</a></code> |                                                                                         |
| **`running`**                     | <code>boolean</code>                                                |                                                                                         |
| **`connected`**                   | <code>boolean</code>                                                |                                                                                         |
| **`observedAt`**                  | <code>number</code>                                                 | Android: Unix milliseconds of the native state/stream observation, not delivery proof.  |
| **`streamEvent`**                 | <code>'message' \| 'open' \| 'keepalive'</code>                     | Android: native stream activity that produced this snapshot. Cleared on reconnect/stop. |
| **`baseUrl`**                     | <code>string</code>                                                 |                                                                                         |
| **`topics`**                      | <code>string[]</code>                                               |                                                                                         |
| **`lastMessageId`**               | <code>string</code>                                                 |                                                                                         |
| **`lastError`**                   | <code>string</code>                                                 |                                                                                         |
| **`retryInSeconds`**              | <code>number</code>                                                 |                                                                                         |
| **`batteryOptimizationsIgnored`** | <code>boolean</code>                                                |                                                                                         |
| **`notificationPermission`**      | <code><a href="#ntfypermissionstate">NtfyPermissionState</a></code> |                                                                                         |


#### NtfyStartOptions

| Prop                     | Type                  | Description                                                                                 |
| ------------------------ | --------------------- | ------------------------------------------------------------------------------------------- |
| **`baseUrl`**            | <code>string</code>   | ntfy server URL, for example https://ntfy.sh. HTTPS is recommended.                         |
| **`topics`**             | <code>string[]</code> | One or more ntfy topic names.                                                               |
| **`token`**              | <code>string</code>   | ntfy access token. Takes precedence over username/password.                                 |
| **`username`**           | <code>string</code>   |                                                                                             |
| **`password`**           | <code>string</code>   |                                                                                             |
| **`initialSince`**       | <code>string</code>   | Initial ntfy `since` value. Defaults to `10m`.                                              |
| **`autoStartOnBoot`**    | <code>boolean</code>  | Restart the foreground service after a device reboot. Defaults to true.                     |
| **`showNotifications`**  | <code>boolean</code>  | Show a local notification for each received ntfy message. Defaults to true.                 |
| **`historyLimit`**       | <code>number</code>   | Maximum number of messages retained by the plugin. Defaults to 100.                         |
| **`foregroundTitle`**    | <code>string</code>   |                                                                                             |
| **`foregroundText`**     | <code>string</code>   |                                                                                             |
| **`serviceChannelId`**   | <code>string</code>   |                                                                                             |
| **`serviceChannelName`** | <code>string</code>   |                                                                                             |
| **`messageChannelId`**   | <code>string</code>   | Base ID for five priority-specific message channels. Defaults to `capacitor_ntfy_messages`. |
| **`messageChannelName`** | <code>string</code>   | Base name shown for the five priority-specific message channels. Defaults to `ntfy 消息`.     |


#### NtfyMessage

| Prop           | Type                                                             | Description                 |
| -------------- | ---------------------------------------------------------------- | --------------------------- |
| **`id`**       | <code>string</code>                                              |                             |
| **`time`**     | <code>number</code>                                              |                             |
| **`event`**    | <code>string</code>                                              |                             |
| **`topic`**    | <code>string</code>                                              |                             |
| **`message`**  | <code>string</code>                                              |                             |
| **`title`**    | <code>string</code>                                              |                             |
| **`priority`** | <code>number</code>                                              |                             |
| **`tags`**     | <code>string[]</code>                                            |                             |
| **`click`**    | <code>string</code>                                              |                             |
| **`expires`**  | <code>number</code>                                              |                             |
| **`raw`**      | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | Original ntfy JSON payload. |


#### NtfyPublishOptions

| Prop           | Type                  |
| -------------- | --------------------- |
| **`baseUrl`**  | <code>string</code>   |
| **`topic`**    | <code>string</code>   |
| **`message`**  | <code>string</code>   |
| **`title`**    | <code>string</code>   |
| **`priority`** | <code>number</code>   |
| **`tags`**     | <code>string[]</code> |
| **`click`**    | <code>string</code>   |
| **`token`**    | <code>string</code>   |
| **`username`** | <code>string</code>   |
| **`password`** | <code>string</code>   |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


### Type Aliases


#### NtfyConnectionState

<code>'stopped' | 'starting' | 'connecting' | 'connected' | 'waitingForNetwork' | 'reconnecting' | 'error'</code>


#### NtfyPermissionState

<code>'granted' | 'denied' | 'prompt' | 'unsupported'</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>

</docgen-api>

## License

MIT
