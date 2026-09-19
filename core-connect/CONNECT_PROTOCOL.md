# Melodist Connect 开放远程控制协议规范 (v1.1)

Melodist Connect 采用标准 WebSocket 进行轻量双向通信。第三方应用（如 Home Assistant、网页遥控器、自定义桌面客户端或自动化脚本）可通过局域网直接连接 TV 或手机端进行播控、状态监听与歌词同步。

---

## 1. 服务发现与连接流程

### 1.1 mDNS / NSD 发现
- **服务类型**：`_melodist-connect._tcp.`
- **默认端口**：`8765`（支持 `8765 ~ 8775` 动态自增）
- **TXT Record 属性**：
  - `id`: 设备唯一 UUID
  - `name`: 设备展示名称（如 `Melodist TV`）
  - `token`: 信任凭证
  - `pin`: 当前 6 位数字配对验证码
  - `host`: 物理 IPv4 地址

### 1.2 WebSocket 端点
- 协议：`ws://<ip>:<port>`

---

## 2. 消息基础模型

所有消息均遵循统一的 JSON 结构：
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
> 推荐直接使用 `data` 传递原始 JSON 对象。为保证兼容性，服务端与客户端均支持解析 `data`（优先）或 `payload`（字符串回退）。

---

## 3. 认证与握手

### 3.1 配对请求 (`pair_request`)
客户端连接后发送的第一条消息：
```json
{
  "action": "pair_request",
  "data": {
    "device": {
      "id": "client-device-uuid",
      "name": "My HomeAssistant Client",
      "type": "MOBILE",
      "token": "previously_saved_token_or_empty"
    },
    "pinCode": "123456"
  }
}
```

### 3.2 配对响应 (`pair_response`)
```json
{
  "action": "pair_response",
  "data": {
    "accepted": true,
    "message": "Auto paired",
    "device": {
      "id": "tv-device-uuid",
      "name": "Melodist TV (Living Room)",
      "type": "TV",
      "token": "allocated_token"
    }
  }
}
```

---

## 4. 主动状态拉取 (RPC)

握手成功后，客户端可主动发送拉取请求：
- **查询播放状态**：`req_get_player_state` -> 服务端即时返回 `event_play_state`
- **查询播放列表**：`req_get_queue_state` -> 服务端即时返回 `event_queue_state`

---

## 5. 播控指令 (Commands)

| 指令 Action | 参数结构 | 说明 |
| :--- | :--- | :--- |
| `cmd_pause` | 无参数 | 暂停播放 |
| `cmd_resume` | 无参数 | 继续播放 |
| `cmd_next` | 无参数 | 下一曲 |
| `cmd_prev` | 无参数 | 上一曲 |
| `cmd_seek` | `{"positionMs": 45000}` | 进度跳转 |
| `cmd_set_volume` | `{"volume": 0.8}` | 设置音量 (0.0 ~ 1.0) |
| `cmd_cycle_loop_mode` | 无参数 | 切换循环模式 (ListRepeat / SingleRepeat / Shuffle) |
| `cmd_switch_tier` | `{"tier": "SQ"}` | 切换音质档位 (Standard / HQ / SQ / HiRes / Master 等) |
| `cmd_trigger_aod` | 无参数 | 触发 / 退出 TV 息屏 AOD 时钟模式 |
| `cmd_open_player` | 无参数 | 在 TV 展开大屏沉浸式播放页 |
| `cmd_toggle_favorite` | `{"songMid": "...", "isFavorite": true}` | 切换当前歌曲红心收藏 |
| `cmd_play_song` | `{"song": {...}, "queue": [...], "index": 0, "startPositionMs": 0}` | 点播/接力指定歌曲并更新队列 |
| `cmd_sync_lyrics` | `{"songMid": "...", "lyrics": [...]}` | 同步单曲歌词到对端缓存池 |

---

## 6. 事件广播 (Events)

### 6.1 播放状态事件 (`event_play_state`)
```json
{
  "action": "event_play_state",
  "data": {
    "currentSong": { "songMid": "...", "name": "...", "singer": "..." },
    "isPlaying": true,
    "positionMs": 35200,
    "durationMs": 240000,
    "volume": 1.0,
    "queueSize": 30,
    "currentIndex": 2,
    "loopMode": "ListRepeat",
    "isAodActive": false,
    "currentTier": "SQ",
    "availableTiers": ["Standard", "HQ", "SQ"],
    "isFavorite": true
  }
}
```

### 6.2 歌词同步事件 (`event_sync_lyrics`)
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
    "sourceDeviceId": "client-uuid"
  }
}
```
