# BeeCountHelper · 由模块接管 AI 交互（方案设计）

> 目标：把「截图记账」中的 AI 网络交互从蜜蜂记账（BeeCount）进程搬到模块进程，
> 消除「宿主进程被系统冻结 → 连接被掐断 → 识别失败」。
>
> 分析对象：`TNT-Likely/BeeCount` @ 43599cc（2026-11-18），对应手机上的 4.0.0 / versionCode 300。

---

## 一、现状：蜜蜂记账是怎么做「截图记账」的

### 1.1 模块侧（现在的做法）

`HostHeadlessEngine` 在 BeeCount 进程里创建一个无界面 `FlutterEngine`，然后通过
MethodChannel 把截图路径送进 Dart：

```
channel  com.tntlikely.beecount/screenshot
method   onScreenshotDetected(path)
```

后续全部由 Dart 完成：AI 调用、JSON 解析、分类/账户匹配、落库、通知。

**没有 Channel 可以「只送结果不送图片」。** 这是本次改造的关键约束。

### 1.2 宿主侧（Dart 链路，逐层）

```
lib/services/automation/auto_billing_service.dart
  processScreenshot(path)
    ├─ 等文件就绪 / 查重
    ├─ AIProviderManager.isCapabilityConfigured(vision)
    └─ aiBookkeeper.fromImage(file, ledgerId, billGuard: PromptBuilder.billGuardForImage)
         │
         ├─ lib/services/ai/ai_bookkeeper.dart        ← 应用层，逐笔落库
         │    ├─ AiExtractionContext.forLedger()      ← 查分类/账户/币种/自定义模板
         │    ├─ AiExtractionEngine.extractFromImage()
         │    └─ BillCreationService.createFromBill() ← 分类匹配、账户匹配、标签、汇率、落库
         │
         └─ lib/ai/core/ai_extraction_engine.dart     ← 底座
              ├─ PromptBuilder.build()                ← 拼 prompt（详下）
              ├─ AIProviderFactory.vision()           ← 真正发 HTTP
              └─ JsonResponseParser.parse()           ← 容错解析成 List<BillInfo>
```

### 1.3 AI 调用协议（实测源码）

`AIProviderFactory.vision()` 按 provider 是否内置分两条路：

| | 内置智谱（`isBuiltIn=true`） | 自定义/OpenAI 兼容（`isBuiltIn=false`） |
|---|---|---|
| 实现 | `flutter_ai_kit_zhipu` 的 `ZhipuGLMProvider` | `_visionOpenAI()` |
| 请求 | 该包内部封装 | `POST {baseUrl}/chat/completions` |

自定义 provider 的请求体（`_visionOpenAI`，源码 1046 行附近）：

```json
{
  "model": "<provider.visionModel>",
  "messages": [{
    "role": "user",
    "content": [
      {"type": "text", "text": "<prompt>"},
      {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,<...>"}}
    ]
  }]
}
```

- 鉴权：`Authorization: Bearer <provider.apiKey>`，`Content-Type: application/json`
- 超时：Dio 全局 60s；内置视觉路径另设 120s
- 响应：取 `choices[0].message.content`，内容是**一段字符串**，里面是 JSON 数组

### 1.4 Prompt 契约（`PromptBuilder`）

强制返回 JSON 数组（单笔也包成 `[{...}]`），字段：`amount` / `time` / `note` /
`category` / `type` / `account` / `from_account` / `to_account` / `tag` / `currency`。
截图路径额外拼 `billGuardForImage` 前置段（不是账单就返回 `[]`）。

### 1.5 落库（`BillCreationService.createFromBill`）

这是**最不该被复制**的一段逻辑，包含：

- 类型判定（expense/income/transfer）
- 分类匹配：完全 → 模糊 → 规则（`CategoryMatcher.smartMatch`）→ 兜底「其他」
- 账户匹配：按币种筛池 → 完全 → 模糊 → 类型映射（余额宝→支付宝）→ 默认账户
- 交易币种推导（账户币种 > AI 币种 > 账本本位币）+ 汇率预拉
- 自动标签：`billingTypeImage` / `billingTypeAi` + AI 自定义标签，标签不存在则创建
- `repo.addTransaction()` → 写 `transactions` + `local_changes`（同步用）
- 通知、`PostProcessor.runC`、附件保存

### 1.6 失败根因（与本方案的关系）

AI 请求由**宿主进程**发起，识别期间（实测 6~10s）宿主被系统冻结：

1. 远程 TLS 连接收不到 ACK → 被服务端/中间设备重置 → `DioException` → 「识别失败」
2. 冻结期间 fake-ip 代理的路由问题（模块已有 `TunnelRoute` 兜这一类）

→ **只要「等网络」这段发生在宿主进程里，就一定会被冻结打到。**
→ 解法不是「让模块也发一次请求」，而是**把这段等待搬出宿主进程**。

---

## 二、三种注入方案对比

| | A. 本地 AI 代理（选定） | B. 模块直写 SQLite | C. 模块算完再喂给宿主 |
|---|---|---|---|
| 做法 | 把宿主的 vision provider 指向模块起的 `127.0.0.1:PORT`，模块代替它访问真·AI，再把响应原样回给宿主 | 模块自己完成 AI，直接 `INSERT INTO transactions` | 需要宿主提供「只接收结构化结果」的入口 |
| 宿主业务逻辑 | **全部保留**（解析/分类/账户/标签/汇率/同步/通知） | 需逐条复刻，极易漂移 | 保留 |
| 宿主进程冻结影响 | 只影响解析写库（亚秒级），等网络发生在模块进程 | 无 | 无 |
| App 是否要改 | 不改 | 不改 | **要改 App** |
| 可行性 | 高（Dart 走 dart:io，不校验明文策略，`http://127.0.0.1` 可用） | 中（同步 `local_changes` 写错会导致云端数据错乱） | 低（App 无此入口） |
| 风险 | provider 配置被 App 回写覆盖 | 数据一致性 + UI 不刷新 | 不可行 |

**选 A。** 它的本质是「把宿主的网络等待搬到模块」，而不是重写记账逻辑。

---

## 三、方案 A 的架构

```
①  磁贴点击
      │
      ▼
②  模块截图 / 写相册（现有 ShotTileService 流程不变）
      │
      ├──────────────────────────────┐
      ▼                              ▼
③  派发 onScreenshotDetected      （模块侧预取，可选）
      │                              AI 直连真服务端，
      ▼                              结果按图片指纹缓存
④  BeeCount：prompt 拼接
      │
      ▼
⑤  POST http://127.0.0.1:46321/chat/completions   ← 因为 vision 被绑到「BeeShot Local」
      │
      ▼
⑥  模块代理进程（前台服务 + wakelock）
      ├─ 命中缓存 → 立刻返回（<50ms）
      └─ 未命中 → 转发到 https://open.bigmodel.cn/api/paas/v4/chat/completions
                  失败重试（429/5xx/断连），超时 90s
      │
      ▼
⑦  BeeCount：JsonResponseParser → BillCreationService → 落库 → 通知
```

### 3.1 为什么冻结不再致命

- 模块进程有前台服务 + `PARTIAL_WAKE_LOCK`，不会因「后台」被冻结；
- 宿主即使中途被冻结，它等的只是一个**本机 socket**：数据已在内核缓冲，
  解冻后 read 立刻返回，不存在「远程连接被重置」；
- 真实网络的所有抖动/重试都在模块侧处理，宿主只感知「一次很快的本地调用」。

### 3.2 关键实现点

1. **provider 注入**（不改 App）
   `SharedPreferences`（`FlutterSharedPreferences.xml`）里两个键：
   - `flutter.ai_providers_v2`：追加
     ```json
     {"id":"beeshot_local","name":"BeeShot Local","isBuiltIn":false,
      "apiKey":"<沿用真 key>","baseUrl":"http://127.0.0.1:46321",
      "textModel":"","visionModel":"glm-4v-flash","audioModel":"",
      "createdAt":"2026-11-20T00:00:00.000"}
     ```
     `isBuiltIn=false` 是为了走 `_visionOpenAI` 那条**简单、可预期**的路径。
   - `flutter.ai_capability_binding_v2`：只把 `visionProviderId` 改成 `beeshot_local`，
     text/speech 保持 `zhipu_glm`（聊天/Agent 不受影响）。
   `AIProviderManager.getProviderForCapability()` **每次调用都读 prefs**，无内存缓存 → 改完即时生效。

2. **HTTP 服务**（模块内，`ServerSocket` 手写，不引第三方库）
   - 监听 `127.0.0.1:46321`，单连接一线程
   - 支持 `POST /chat/completions`，按 `Content-Length` 读 body
   - 回 `200` + `Content-Length` + `application/json`（不走 chunked）
   - 非目标路径回 `404`，无 body

3. **上游调用**
   - 用 `HttpsURLConnection` 直连真 baseUrl（默认 `https://open.bigmodel.cn/api/paas/v4`）
   - 原样转发 body 与 `Authorization`
   - 重试策略：断连/429/5xx 退避重试（3 次，1s/3s/7s）
   - 缓存键：请求体里图片 base64 的 SHA-256 → 同一张图重试直接命中

4. **降级（必须做，避免把用户搞成「完全不能用」）**
   - 模块服务未启动 / 端口不通 → 宿主会 `DioException` → 显示识别失败（与现状一致）
   - App 回写 prefs 抹掉 `beeshot_local` → 每次派发前**重新校验并补写**；找不到就直接跳过代理
   - 一键关闭开关：把 binding 还原成 `zhipu_glm` 即可恢复原状

5. **可观测性**
   - 模块把每次请求写日志：图片指纹、上游耗时、重试次数、返回条数
   - 设置页显示「代理已接管 N 次 / 最近失败原因」

### 3.3 已知风险

| 风险 | 影响 | 对策 |
|---|---|---|
| App 任意改设置时重写 prefs 文件，抹掉注入的 provider | vision 绑定指向不存在的 id → 识别失败 | 派发前校验并补写；后续可升级为 hook `SharedPreferencesImpl.getString` |
| BeeCount 的 `sync_providers` 把我们注入的 provider 推到云端 | 其他设备出现「BeeShot Local」 | 直接写文件、不走 `saveProviders`（不触发 `onConfigChanged`） |
| 明文 HTTP 被拦 | 代理收不到请求 | Dart `dart:io` 不校验 Android 明文策略；实机验证（若失败改走 `LocalServerSocket` + 本地回环） |
| 端口被占 | 启动失败 | 端口动态选择，写入 provider.baseUrl |
| 模块自身被冻结 | 等待时间变长 | 前台服务 + wakelock；必要时 root 加白名单 |

---

## 四、验证计划（实机 2211133C / Android 17 / KernelSU）

1. `adb logcat` 抓 `AutoBilling` + 模块日志，确认请求确实到达 `127.0.0.1:46321`
2. 正常路径：点磁贴 → 3 秒内出账 → 检查 `transactions` 新增行
3. 冻结路径：`adb shell am freeze`/后台压测期间点磁贴，确认不再出现「识别失败」
4. 回归：App 内聊天记账、手动记录、小组件刷新不受影响（binding 只改 vision）
5. 关闭开关 → 恢复原 provider → 行为回到改造前

---

## 五、与「完全取代」的差距（诚实说明）

本方案接管的是**与 AI 的网络交互**（请求 + 重试 + 超时 + 缓存），
记账信息的**解析与落库仍由宿主完成**。

这样做的原因：App 没有「只接收结构化账单」的入口，
而 `BillCreationService` 里的分类/账户/标签/汇率/同步逻辑一旦复制就会漂移。
如果后续确实要做到「模块拿到账单后自己落库」，需要：
- 复刻 `BillCreationService` 全部规则，并稳定跟踪 App 的表结构变更；
- 或给 App 提 PR 增加一个「外部记账入口」（Channel/Intent/本地 HTTP），
  由 App 自己落库 —— 这是最干净的长期方案，但需要改上层应用。
