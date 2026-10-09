# Melodist Connect 开放远程控制与流媒体协议规范 (v1.2)

Melodist Connect 使用 WebSocket 进行控制指令与状态同步，通过 HTTP Stream Proxy 提供局域网音频与媒体封面串流服务。

---

## 1. 服务发现与连接流程

### 1.1 mDNS / NSD 发现
- **服务类型**：`_melodist-connect._tcp.`
- **默认端口**：`8765`（端口冲突时在 `8765 ~ 8775` 范围递增）
- **TXT Record 属性**：
  - `id`: 设备 UUID
  - `name`: 设备名称
  - `token`: 鉴权凭证
  - `pin`: 6 位数字配对码
  - `host`: IPv4 地址

### 1.2 二维码配对协议 (`QrPairData`)
TV 端配对二维码数据结构：
```json
{
  "version": 1,
  "deviceId": "<tv_device_id>",
  "deviceName": "<tv_device_name>",
  "host": "<tv_ip>",
  "port": 8765,
  "token": "<allocated_or_empty_token>",
  "pinCode": "<pin_code>"
}
```

### 1.3 WebSocket 端点
- 访问地址：`ws://<ip>:<port>`

---

## 2. 消息基础模型

WebSocket 数据帧 JSON 结构：
```json
{
  "id": "<message_uuid>",
  "action": "<action_name>",
  "data": { ... },
  "payload": "<optional_fallback_json_string>",
  "timestamp": <timestamp_ms>
}
```
> [!NOTE]
> `data` 为 JSON 结构体；`payload` 为序列化字符串。优先解析 `data`，当 `data` 为 null 时解析 `payload`。

---

## 3. 认证、会话与保活

### 3.1 配对请求 (`pair_request`)
客户端建立 WebSocket 连接后的握手指令：
```json
{
  "action": "pair_request",
  "data": {
    "device": {
      "id": "<client_device_id>",
      "name": "<client_device_name>",
      "type": "MOBILE",
      "host": "<client_ip>",
      "port": 8765,
      "token": "<saved_token_or_empty>"
    },
    "pinCode": "<pin_code>"
  }
}
```

### 3.2 配对响应 (`pair_response`)
服务端验证响应：
```json
{
  "action": "pair_response",
  "data": {
    "accepted": true,
    "message": "<status_message>",
    "device": {
      "id": "<tv_device_id>",
      "name": "<tv_device_name>",
      "type": "TV",
      "token": "<granted_session_token>"
    }
  }
}
```

### 3.3 断开连接 (`disconnect`)
注销连接指令：
```json
{
  "action": "disconnect",
  "data": {}
}
```

### 3.4 心跳探活 (`ping` / `pong`)
心跳探活数据帧：
- 发送方下发 `{"action": "ping"}`
- 接收方回执 `{"action": "pong"}`

### 3.5 断线重连与退避机制
- **主动断开**：发送 `disconnect` 指令并置位 `isManualDisconnect`，重置计数并终止重试。
- **重试限制**：非主动断开时最多重试 5 次，间隔在 2 秒至 10 秒递增。
- **重试条件**：仅对已完成握手的设备执行重连；出现连接异常或目标不可达时终止重试。

---

## 4. 状态查询 (RPC)

客户端主动查询指令与对应响应事件：
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
| `cmd_seek` | `{"positionMs": <position_ms>}` | 跳转播放进度 |
| `cmd_set_volume` | `{"volume": <float_volume>}` | 设置音量（0.0 ~ 1.0） |
| `cmd_cycle_loop_mode` | 无参数 | 轮换循环模式（`ListRepeat` / `SingleRepeat` / `Shuffle`） |
| `cmd_switch_tier` | `{"tier": "<tier_name>"}` | 切换音质档位（`Standard` / `HQ` / `SQ` / `HiRes` / `Master`） |
| `cmd_trigger_aod` | 无参数 | 触发或退出息屏时钟模式 |
| `cmd_open_player` | 无参数 | 展开全屏播放界面 |
| `cmd_toggle_favorite` | `{"song": <optional_song_object>, "songMid": "<song_mid>", "isFavorite": <boolean>}` | 切换歌曲收藏状态 |
| `cmd_enqueue_next` | `{"song": <song_object>, "audioSource": <optional_audio_source>}` | 插入下一首播放 |
| `cmd_play_song` | 见详细数据模型 | 点播曲目并设置当前队列 |
| `cmd_sync_queue_chunk` | 见详细数据模型 | 队列分批同步数据分片 |
| `cmd_gesture_swipe` | 见详细数据模型 | 接管模式滑动卡片手势同步 |
| `cmd_sync_lyrics_scroll` | `{"lineIndex": <line_index>, "isUserScrolling": <boolean>, "timestamp": <timestamp_ms>}` | 同步歌词行偏移与滚动状态 |
| `cmd_sync_lyrics` | 见详细数据模型 | 同步单曲歌词及校准偏移量 |

### 5.1 点播曲目指令详情 (`cmd_play_song`)
```json
{
  "action": "cmd_play_song",
  "data": {
    "song": {
      "songId": <song_id>,
      "songMid": "<song_mid>",
      "name": "<song_name>",
      "singer": "<singer_name>",
      "album": "<album_name>",
      "coverUrl": "<cover_url>",
      "mediaMid": "<stream_url_or_media_mid>",
      "localFilePath": "<local_file_path_or_null>"
    },
    "queue": [ /* Song 列表 */ ],
    "index": <queue_index>,
    "startPositionMs": <start_position_ms>,
    "qualityTier": "<quality_tier>",
    "audioSource": {
      "sourceType": "STREAM_PROXY",
      "streamUrl": "http://<stream_host>:<stream_port>/stream/local?path=<encoded_path>",
      "headers": {}
    }
  }
}
```
> [!NOTE]
> `audioSource.sourceType` 取值定义：
> - `DIRECT_API`: 直接请求流媒体 API；
> - `STREAM_PROXY`: 从控制端 HTTP 代理拉取音频流；
> - `DIRECT_WEBDAV`: 直接向 WebDAV 服务器拉取音频流。

### 5.2 队列分片同步 (`cmd_sync_queue_chunk`)
队列长度超过 100 首时，按批次同步队列数据：
```json
{
  "action": "cmd_sync_queue_chunk",
  "data": {
    "syncId": "<uuid>",
    "chunkIndex": 0,
    "totalChunks": 5,
    "songs": [ /* 分片 Song 列表，单批 150 首 */ ],
    "targetMid": "<current_playing_song_mid>"
  }
}
```
服务端接收完全部分片后更新播放队列，并广播 `event_queue_state`。

### 5.3 卡片手势指令 (`cmd_gesture_swipe`)
接管模式下卡片滑动手势数据帧：
```json
{
  "action": "cmd_gesture_swipe",
  "data": {
    "state": "DRAGGING",
    "fraction": <float_fraction>,
    "targetFraction": <float_target_fraction>,
    "durationMs": <duration_ms>,
    "timestamp": <timestamp_ms>
  }
}
```
- `state`:
  - `DRAGGING`: 拖拽状态，`fraction` 取值范围 `[-1.0, 1.0]`；
  - `SETTLING`: 释放手势，进入吸附状态；
  - `CANCEL`: 取消手势，复位归零；
  - `IDLE`: 空闲状态。

---

## 6. 事件广播 (Events)

### 6.1 播放器状态事件 (`event_play_state`)
```json
{
  "action": "event_play_state",
  "data": {
    "currentSong": {
      "songMid": "<song_mid>",
      "name": "<song_name>",
      "singer": "<singer_name>",
      "coverUrl": "<cover_url>"
    },
    "isPlaying": <boolean>,
    "positionMs": <position_ms>,
    "durationMs": <duration_ms>,
    "volume": <float_volume>,
    "queueSize": <queue_size>,
    "currentIndex": <current_index>,
    "loopMode": "ListRepeat",
    "isAodActive": <boolean>,
    "prevSong": { "songMid": "<prev_mid>", "name": "<prev_name>", "coverUrl": "<prev_cover>" },
    "nextSong": { "songMid": "<next_mid>", "name": "<next_name>", "coverUrl": "<next_cover>" },
    "currentTier": "SQ",
    "availableTiers": ["Standard", "HQ", "SQ", "HiRes", "Master"],
    "isFavorite": <boolean>,
    "isRadioMode": <boolean>,
    "lyricOffsetMs": <offset_ms>
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
        "songId": <song_id>,
        "songMid": "<song_mid>",
        "name": "<song_name>",
        "singer": "<singer_name>",
        "coverUrl": "<cover_url>"
      }
    ],
    "currentIndex": <current_index>
  }
}
```

### 6.3 歌词同步事件 (`event_sync_lyrics`)
```json
{
  "action": "event_sync_lyrics",
  "data": {
    "songMid": "<song_mid>",
    "title": "<song_title>",
    "singer": "<singer_name>",
    "lyrics": [
      {
        "timestampMs": <line_timestamp_ms>,
        "text": "<lyric_line_text>",
        "transText": "<optional_translation_text>"
      }
    ],
    "sourceDeviceId": "<source_device_id>",
    "lyricOffsetMs": <offset_ms>,
    "timestamp": <timestamp_ms>
  }
}
```

---

## 7. 局域网流媒体代理协议 (HTTP Stream Server)

控制端 HTTP Server 默认端口 `8766`，提供音频与图片代理服务。

### 7.1 服务路由端点

| 端点路径 | 请求方法 | 参数 | 功能说明 |
| :--- | :--- | :--- | :--- |
| `/stream/local` | `GET`, `HEAD` | `path=<url_encoded_file_path>` | 本地音频流式输出，支持 Range 请求 |
| `/cover/local` | `GET`, `HEAD` | `path=<url_encoded_cover_path>` | 本地封面图片输出 |
| `/cover` | `GET`, `HEAD` | `name=<cached_cover_filename>` | 缓存封面图片输出 |
| `/stream/webdav` | `GET`, `HEAD` | `server=<server_id>&href=<url_encoded_href>` | WebDAV 音频分片转发输出 |
| `/stream/proxy` | `GET`, `HEAD` | `url=<url_encoded_target_url>` | 公网音频流代理转发输出 |

### 7.2 特性支持
- **HTTP Range**：支持 `Range: bytes=start-end`、`Range: bytes=start-` 及 `Range: bytes=-suffix` 请求，返回 HTTP 206 Partial Content。
- **HEAD 预检**：支持 HEAD 方法，返回 `Content-Length`、`Accept-Ranges`、`Content-Type` 响应头。
- **MIME Type**：根据文件扩展名映射 `Content-Type`（`audio/flac`、`audio/mpeg`、`audio/wav`、`audio/ogg`、`audio/mp4`、`image/webp`、`image/jpeg` 等）。

### 7.3 媒体托管与流直通规范
- **`mediaMid` 网络流直通**：
  当 `song.mediaMid` 为 `http://` 或 `https://` 协议 URL 时：
  1. 设置 `AudioSourceDescriptor(sourceType = STREAM_PROXY, streamUrl = song.mediaMid)`；
  2. 不执行本地扫描库检索与代理流创建。
- **命名空间前缀转换 (`pc_local_` / `local_`)**：
  1. 服务端广播音频数据时，本地文件 `songMid` 添加 `pc_local_` 前缀，`isLocal` 标记为 `false`；
  2. 接收端下发 `cmd_play_song` 或 `cmd_enqueue_next` 时，服务端将 `pc_local_` 还原为 `local_` 标识。

---

## 8. 远控工作模式与双向通信

### 8.1 远控模式 (`RemoteControlMode`)
- **`TAKEOVER`（接管模式）**：
  - 控制端接管目标设备播控与状态；
  - 控制端本地静音，同步 UI 渲染与媒体通知；
  - 受控端回传播放状态。
- **`BROWSE`（独立浏览模式）**：
  - 控制端与目标设备独立运行；
  - 仅在显式操作时向目标设备发送指令。

### 8.2 双向控制 (Bidirectional Commands)
协议支持双向控制：
- 控制端向服务端下发 `cmd_*` 指令；
- 服务端向控制端反向发送 `cmd_play_song` / `cmd_next` / `cmd_prev` 指令，控制端启动 HTTP Stream Server 并返回流地址。

### 8.3 切歌控制权规则 (Subordinate Authority Gate)
在 `TAKEOVER` 模式下：
1. 控制端仅在用户主动操作时转发切歌指令；
2. 控制端本地播放内核事件（加载重试、超时、`STATE_ENDED` 等）不触发 `cmd_next` / `cmd_prev`；
3. 播放推进由宿主端执行，并通过 `event_play_state` 广播状态。
