# MICx 直播化接手计划

## 当前基线

- 底座项目: `zoyluoblue/mc_aiplayer`
- 本地路径: `/Users/micx/MC-DeepSeek-Baritone-Live`
- Minecraft: `1.21.3`
- Fabric Loader: `0.18.4`
- Fabric API: `0.114.1+1.21.3`
- Java: `21`
- 架构判断: 服务端假玩家 Bob + DeepSeek 工具调用 + 确定性 Task 状态机。

## 已验证

- 工作树初始干净。
- `AIPlayerManager.spawn()` 和 `respawnFromRecord()` 当前源码已强制 `GameMode.SURVIVAL`，不会继承召唤者创造模式。
- `compileJava compileClientJava` 已通过；`/aibot camera` 目前只做编译验证，尚未做游戏内运行期验证。
- 构建卡顿根因是系统代理导致 `libraries.minecraft.net` TLS 握手失败；禁用 Java 系统代理后通过。

## 固定编译命令

```bash
JAVA_HOME="/Users/micx/Library/Java/JavaVirtualMachines/jdk-21.0.11.jdk/Contents/Home" \
./gradlew compileJava compileClientJava --no-daemon \
  -Djava.net.useSystemProxies=false \
  -Dhttp.proxyHost= \
  -Dhttps.proxyHost= \
  -Dorg.gradle.internal.http.connectionTimeout=45000 \
  -Dorg.gradle.internal.http.socketTimeout=45000
```

每次 Java/Gradle 任务后执行:

```bash
ps aux | grep java
```

## 第一阶段目标

1. 保持 `1.21.3`，先不升级 `1.21.8` 或 `26.x`。
2. 不急着接标准 Baritone；先验证项目自带 A* / Task 是否够直播用。
3. 搭本地 dedicated Fabric server，主客户端玩，第二客户端旁观 Bob。
4. OBS 捕获主视角 + Bob 视角 + AI 状态 overlay。
5. 加直播人格、礼物/弹幕事件注入和 TTS。

## Camera 待验证矩阵

- 摄影机玩家已是 spectator 时，`/aibot camera bind Camera Bob` 能切到 Bob 第一视角。
- 摄影机玩家不是 spectator 时，命令拒绝执行且不改其游戏模式。
- 摄影机和 Bob 不同维度时，命令先传送摄影机到 Bob 所在世界再绑定视角。
- `/aibot camera release Camera` 后视角回到 Camera 自己。
- Bob despawn / 断线 / 服务端停止后，摄影机账号不被强制改模式。

## 优先改造清单

- `camera` 辅助命令: 绑定摄影机玩家旁观 Bob。
- `overlay` 数据出口: Bob 状态、当前任务、DeepSeek 回复、人格、礼物事件。
- 人格系统: 正常、毒舌、怂包、狂战士、哲学家、反骨、萌新。
- 礼物桥: 先 mock JSONL/WebSocket，再接 B站弹幕/礼物。
- 开播 SOP: DeepSeek key、server、Bob、摄影机、OBS、TTS 一键检查。

## Baritone 判断

- 标准 Baritone 是客户端路径系统，不适合直接控制服务端假玩家 Bob。
- 如果自带寻路实测不够，再评估 `Automatone` 或抽象 `Navigator` 接口。
- 短期优先修运行期失败项和直播体验，而不是替换导航核心。
