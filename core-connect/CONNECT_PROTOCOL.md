# Melodist Connect 开放远程控制与流媒体协议规范 (v1.2)

Melodist Connect 采用标准 WebSocket 进行双向控制与状态同步，并结合轻量 HTTP Stream Proxy 实现局域网音频与媒体封面串流。支持 TV 端（大屏服务端）与手机端（遥控与串流端）深度互联，第三方应用（如 Home Assistant、自动化脚本、自定义遥控器）亦可通过局域网直接接入进行播控、状态监听与媒体接力。

---

## 1. 服务发现与连接流程

### 1.1 mDNS / NSD 发现
- **服务类型**：`_melodist-connect._tcp.`
- **默认端口**：`8765`（支持 `8765 ~ 8775` 端口冲突自增）
- **TXT Record 属性**：
  - `id`: 设备唯一 UUID
  - `name`: 设备展示名称（如 `Melodist TV`）
  - `token`: 信任凭证
  - `pin`: 当前 6 位数字配对验证码
  - `host`: 物理 IPv4 地址

### 1.2 二维码配对协议 (`QrPairData`)
TV 端大屏展示的配对二维码内容为标准 JSON 字符串：
```json
{
  "version": 1,
  "deviceId": "tv-device-uuid",
  "deviceName": "客厅 TV",
  "host": "192.168.1.100",
  "port": 8765,
  "token": "allocated_or_empty_token",
  "pinCode": "123456"
}
```

### 1.3 WebSocket 端点
- 协议端点：`ws://<ip>:<port>`

---

## 2. 消息基础模型

所有 WebSocket 数据帧均遵循统一的 JSON 消息封套：
```json
{
  "id": "uuid-string",
  "action": "action_name",
  "data": { ... },
  "payload": "{\"deprecated\":\"for_v1_compatibility\"}",
  "timestamp": 1726700000000
}
```
> [!NOTE]
> `data` 承载原始 JSON 结构体；`payload` 字符串仅用于向后兼容。解析器优先读取 `data`，当 `data` 为 null 时回退解析 `payload`。

---

## 3. 认证、会话与保活

### 3.1 配对请求 (`pair_request`)
客户端建立 WebSocket 连接后，发送的第一条握手指令：
```json
{
  "action": "pair_request",
  "data": {
    "device": {
      "id": "client-device-uuid",
      "name": "我的手机",
      "type": "MOBILE",
      "host": "192.168.1.101",
      "port": 8765,
      "token": "saved_token_or_empty"
    },
    "pinCode": "123456"
  }
}
```

### 3.2 配对响应 (`pair_response`)
服务端验证 PIN 码或 Token 后返回：
```json
{
  "action": "pair_response",
  "data": {
    "accepted": true,
    "message": "Auto paired",
    "device": {
      "id": "tv-device-uuid",
      "name": "客厅 TV",
      "type": "TV",
      "token": "granted_session_token"
    }
  }
}
```

### 3.3 断开连接 (`disconnect`)
客户端或服务端主动注销连接时下发：
```json
{
  "action": "disconnect",
  "data": {}
}
```

### 3.4 心跳探活 (`ping` / `pong`)
保持长连接活性及检测半开连接：
- 发送方下发 `{"action": "ping"}`
- 接收方回执 `{"action": "pong"}`

---

## 4. 主动状态查询 (RPC)

握手完成后，客户端可按需主动查询即时数据：
- **查询播放状态**：`{"action": "req_get_player_state"}` -> 服务端广播 `event_play_state`
- **查询播放队列**：`{"action": "req_get_queue_state"}` -> 服务端广播 `event_queue_state`

---

## 5. 播控与交互指令 (Commands)

| 指令 Action | 参数结构 | 说明 |
| :--- | :--- | :--- |
| `cmd_pause` | 无参数 | 暂停播放 |
| `cmd_resume` | 无参数 | 继续播放 |
| `cmd_next` | 无参数 | 切至下一曲 |
| `cmd_prev` | 无参数 | 切至上一曲 |
| `cmd_seek` | `{"positionMs": 45000}` | 播放进度跳转 |
| `cmd_set_volume` | `{"volume": 0.8}` | 设置音量（0.0 ~ 1.0） |
| `cmd_cycle_loop_mode` | 无参数 | 轮换循环模式（`ListRepeat` / `SingleRepeat` / `Shuffle`） |
| `cmd_switch_tier` | `{"tier": "SQ"}` | 切换音质档位（`Standard` / `HQ` / `SQ` / `HiRes` / `Master` 等） |
| `cmd_trigger_aod` | 无参数 | 触发或退出 TV 息屏 AOD 时钟模式 |
| `cmd_open_player` | 无参数 | 在 TV 端展开大屏全屏播放界面 |
| `cmd_toggle_favorite` | `{"songMid": "...", "isFavorite": true}` | 切换歌曲收藏状态 |
| `cmd_enqueue_next` | `{"song": {...}, "audioSource": {...}}` | 将指定曲目插入下一首优先播放 |
| `cmd_play_song` | 见下方详细模型 | 点播曲目、接力播放并重置/同步队列 |
| `cmd_gesture_swipe` | 见下方详细模型 | 接管模式下的实时跟手滑动卡片手势联动 |
| `cmd_sync_lyrics_scroll` | `{"lineIndex": 5, "isUserScrolling": true}` | 同步歌词手动滚动行偏移与跟手状态 |
| `cmd_sync_lyrics` | 见下方详细模型 | 同步单曲歌词及声学校准偏移量至对端缓存池 |

### 5.1 点播曲目指令详情 (`cmd_play_song`)
```json
{
  "action": "cmd_play_song",
  "data": {
    "song": {
      "songId": 123456,
      "songMid": "0039MnYb0qxYhV",
      "name": "晴天",
      "singer": "周杰伦",
      "album": "叶惠美",
      "coverUrl": "http://192.168.1.101:8766/cover/local?path=..."
    },
    "queue": [ /* Song 列表 */ ],
    "index": 0,
    "startPositionMs": 0,
    "qualityTier": "SQ",
    "audioSource": {
      "sourceType": "STREAM_PROXY",
      "streamUrl": "http://192.168.1.101:8766/stream/local?path=...",
      "headers": {}
    }
  }
}
```
> [!NOTE]
> `audioSource.sourceType` 支持以下类型：
> - `DIRECT_API`: TV 本地网络直接请求流媒体 API；
> - `STREAM_PROXY`: TV 从手机端局域网 HTTP 代理拉取音频流（适用于本地音乐、WebDAV 代理或离线中转）；
> - `DIRECT_WEBDAV`: TV 直连自身挂载的 WebDAV 服务器。

### 5.2 实时跟手手势指令 (`cmd_gesture_swipe`)
接管模式（TAKEOVER）下，手机端手势滑动联动 TV 大屏走马灯动效：
```json
{
  "action": "cmd_gesture_swipe",
  "data": {
    "state": "DRAGGING",
    "fraction": -0.45,
    "targetFraction": -1.0,
    "durationMs": 200,
    "timestamp": 1726700000000
  }
}
```
- `state`:
  - `DRAGGING`: 手指拖拽中，`fraction` 在 `-1.0`（推向下一首）与 `1.0`（推向上首）之间实时变动；
  - `SETTLING`: 释放手势，执行吸附结算动画；
  - `CANCEL`: 取消手势，平滑复位归零；
  - `IDLE`: 空闲状态。

---

## 6. 事件广播 (Events)

### 6.1 播放器状态事件 (`event_play_state`)
```json
{
  "action": "event_play_state",
  "data": {
    "currentSong": {
      "songMid": "0039MnYb0qxYhV",
      "name": "晴天",
      "singer": "周杰伦",
      "coverUrl": "..."
    },
    "isPlaying": true,
    "positionMs": 35200,
    "durationMs": 240000,
    "volume": 1.0,
    "queueSize": 30,
    "currentIndex": 2,
    "loopMode": "ListRepeat",
    "isAodActive": false,
    "prevSong": { "songMid": "...", "name": "...", "coverUrl": "..." },
    "nextSong": { "songMid": "...", "name": "...", "coverUrl": "..." },
    "currentTier": "SQ",
    "availableTiers": ["Standard", "HQ", "SQ", "HiRes"],
    "isFavorite": true,
    "isRadioMode": false,
    "lyricOffsetMs": 150
  }
}
```

### 6.2 播放队列变更事件 (`event_queue_state`)
```json
{
  "action": "event_queue_state",
  "data": {
    "queue": [
      {
        "songId": 101,
        "songMid": "webdav_server1_hash1",
        "name": "曲目 1",
        "singer": "歌手",
        "coverUrl": "file:///data/user/0/org.melodist.tv/cache/covers/webdav_hash1.webp"
      }
    ],
    "currentIndex": 0
  }
}
```

### 6.3 歌词同步事件 (`event_sync_lyrics`)
```json
{
  "action": "event_sync_lyrics",
  "data": {
    "songMid": "0039MnYb0qxYhV",
    "title": "晴天",
    "singer": "周杰伦",
    "lyrics": [
      {
        "timestampMs": 14200,
        "text": "故事的小黄花",
        "transText": ""
      }
    ],
    "sourceDeviceId": "client-uuid",
    "lyricOffsetMs": 150,
    "timestamp": 1726700000000
  }
}
```

---

## 7. 局域网流媒体代理协议 (HTTP Stream Server)

当在移动端接管 TV 播放手机本地音频、本地 WebDAV 或在离线代理模式下工作时，手机端启动内嵌 HTTP Server（默认端口 `8766`），向 TV 提供音频和图片串流。

### 7.1 服务路由端点

| 端点路径 | 请求方法 | 典型参数 | 功能描述 |
| :--- | :--- | :--- | :--- |
| `/stream/local` | `GET` | `path=/storage/emulated/0/Music/...` | 本地音频流式输出（支持 HTTP Range 断点续传与 Seek） |
| `/cover/local` | `GET` | `path=/data/user/0/.../cover.webp` | 本地与 WebDAV 专辑封面图片代理输出 |
| `/stream/webdav` | `GET` | `server=server_id&href=/Music/...` | 手机代为拉取 WebDAV 音频分片并转发至 TV |
| `/stream/proxy` | `GET` | `url=https%3A%2F%2F...` | TV 离线模式下，由手机代理请求公网 CDN 音频流 |

### 7.2 特性支持
- **HTTP Range 规范**：全量实现 `Range: bytes=start-end` 请求响应（HTTP 206 Partial Content），支持大文件任意微秒精确 Seek。
- **MIME Type 映射**：依据文件扩展名自动输出标准 `Content-Type`（如 `audio/flac`、`audio/mpeg`、`image/webp` 等）。

---

## 8. 远控工作模式与双向对称性

### 8.1 远控模式 (`RemoteControlMode`)
- **`TAKEOVER`（全面接管模式）**：
  - 手机播放器内核与 TV 深度绑定；
  - 手机端进行的点播、切歌、音量调节、播放列表变动及卡片拖拽手势均直接作用于 TV；
  - 手机默认开启本地静音，仅同步 UI 渲染与系统媒体通知；TV 播放状态毫秒级回传手机。
- **`BROWSE`（独立浏览模式）**：
  - 手机端保持本地独立发声与播放，大屏作为独立设备运行；
  - 仅在用户主动点击“在 TV 上播放”或投播菜单时才向 TV 发送指令。

### 8.2 双向对称控制 (Bidirectional Commands)
Connect 协议具备对等双向性：
- 常规场景：手机作为控制端向 TV 下发 `cmd_*` 指令；
- 反向接力场景：当 TV 界面通过遥控器触发了一首仅存在于手机本地文件系统的曲目时，TV 端通过 [TvConnectServer.kt](file:///home/yuzuki/CodeSpace/melodist-tv/core-connect/src/main/kotlin/org/melodist/core/connect/server/TvConnectServer.kt) 反向向手机广播 `cmd_play_song` / `cmd_next` / `cmd_prev`，手机捕获后自动建立 HTTP Stream Server 代理并把流回传给 TV 播放。

