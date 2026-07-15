#!/usr/bin/env python3
"""抖音直播事件监听 → aibot 桥(127.0.0.1:8787)

macOS 没有抖音直播伴侣,用网页直播间当信源:
Playwright 打开 https://live.douyin.com/<房间号>,注入 MutationObserver 监听聊天区,
把新增节点按文本特征分成三类事件回传:
  gift   :命中"送出"        → 解析(用户,礼物,数量)→ 连击聚合 → POST /gift
  chat   :昵称冒号打字弹幕   → 批量 POST /danmaku(kind=chat),bot 空闲时回观众/点名插队
  follow :命中"关注了主播"   → POST /danmaku(kind=follow),bot TTS 念名字感谢(mod 端 LRU 去重)
礼物名→动作映射在 minecraft/config/aibot_gifts.json,去重/冷却由桥端负责(429)。

用法:
  python3 douyin_gift_watch.py 123456789              # 房间号或完整直播间 URL
  python3 douyin_gift_watch.py 123456789 --headless   # 登录态存好后可无头跑
  python3 douyin_gift_watch.py --test 玫瑰            # 不开浏览器,直接向桥发一发测试礼物

选项:
  --bridge URL       礼物桥地址(默认 http://127.0.0.1:8787/gift;/danmaku 同主机自动推导)
  --token TOK        aibot_gifts.json 配了 token 才需要
  --user NAME        --test 模式的送礼人(默认 测试观众)
  --count N          --test 模式的数量(默认 1)
  --dry-run          只打印解析出的事件,不 POST
  --no-danmaku       只采集礼物(回退旧行为)
  --danmaku-flush S  弹幕批量间隔秒(默认 2.0)
  --danmaku-batch N  单批弹幕上限(默认 10)

健壮性(直播中途不再需要人工重启):
  浏览器崩溃/页面断开 → 自动重连重挂观察器(指数退避 5/10/20/30s,存活 >120s 退避复位);
  每 60s 心跳打印转发计数 + 复查桥健康;POST 连续失败 ≥5 醒目告警;
  页面 URL 疑似跳到登录页时提示登录态失效。

首次使用:会弹出真实 Chromium 窗口,出现登录/滑块人工处理一次;
登录态持久化在 ~/.aibot/douyin-profile,之后可加 --headless。
选择器无关:不依赖抖音的 class 名,只按文本特征 + img alt 解析,前端改版存活率高。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

PROFILE_DIR = Path.home() / ".aibot" / "douyin-profile"

# 注入页面的监听器:突变观察器扫新增节点,按文本特征分类回传 (kind, 原始文本, 图片alt列表)。
# 分类顺序是防伪的关键:冒号行(观众打字)最先摘走——"张三:我送出了嘉年华"永远进不了礼物分支
# (与 Python 侧 parse_gift 防伪判 1 同一事实,双层纵深)。不用抖音 class 选择器(改版就失效)。
OBSERVER_JS = r"""
() => {
  if (window.__aibotGiftHooked) return;
  window.__aibotGiftHooked = true;
  const seen = new Map(); // 签名 -> 时间戳,页面侧 800ms 去重(纯重复渲染;签名含 kind)
  const emit = (kind, text, alts) => {
    const sig = kind + '|' + text + '|' + alts.join(',');
    const now = Date.now();
    const last = seen.get(sig);
    if (last && now - last < 800) return;
    seen.set(sig, now);
    if (seen.size > 500) {
      const cutoff = now - 5000;
      for (const [k, v] of seen) { if (v < cutoff) seen.delete(k); }
    }
    window.__aibotGift({ kind, text, alts });
  };
  const scan = (node) => {
    if (!node || node.nodeType !== 1) return;
    const text = (node.innerText || '').replace(/\s+/g, ' ').trim();
    if (!text || text.length > 120) return;
    const alts = Array.from(node.querySelectorAll('img[alt]'))
      .map(i => (i.getAttribute('alt') || '').trim())
      .filter(a => a && a.length <= 20);
    if (/^[^:：]{1,40}[:：]/.test(text)) { emit('chat', text, alts); return; }
    if (/送出/.test(text)) { emit('gift', text, alts); return; }
    if (/关注了主播/.test(text)) { emit('follow', text, alts); return; }
  };
  new MutationObserver(muts => {
    for (const m of muts) {
      for (const n of m.addedNodes) scan(n);
    }
  }).observe(document.body, { childList: true, subtree: true });
  return true;
}
"""


def parse_gift(text: str, alts: list[str], strict: bool = True):
    """从聊天消息文本解析 (用户, 礼物名, 数量);解析不出礼物名时回退礼物图标的 img alt。

    常见形态: "小明送出了小心心" / "小明 送出了 玫瑰 ×5" / "小明送出了 ×1"(礼物是图片,名在 alt)

    防伪判(strict,默认开):观众在弹幕里**打字**"张三送出了嘉年华"不能被当成真礼物——
    1. 普通聊天渲染成"昵称:内容",昵称后带冒号;平台生成的礼物行没有这个冒号。
       "送出"出现在冒号之后 = 打字弹幕,直接拒。(页面侧分类已先把冒号行摘走,这里是纵深防御)
    2. 真礼物行必带礼物图标 <img alt="礼物名">,打字注入不了图片;表情包虽然也是 img,
       但 alt 是"[捂脸]"这类方括号形态,过滤掉。没有有效礼物图标 alt = 拒。
    --lax 关掉第 2 条(开播首测配 --dry-run 校准 DOM 形态用)。
    """
    import re

    # 防伪判 1:昵称后紧跟冒号 = 打字弹幕("昵称:……送出……"),不是平台礼物行。
    if re.match(r"^\s*[^:：]{1,40}[:：]", text):
        return None
    # 表情包 alt("[捂脸]")不算礼物图标。
    gift_alts = [a for a in alts if not re.fullmatch(r"\[.{1,10}\]", a)]
    # 防伪判 2(strict):没有礼物图标 alt 的"送出"文本一律不信。
    if strict and not gift_alts:
        return None

    m = re.search(r"^(.{1,40}?)\s*送出了?\s*(.*)$", text)
    if not m:
        return None
    user = m.group(1).strip()
    rest = m.group(2).strip()
    count = 1
    cm = re.search(r"[×xX*]\s*(\d+)", rest)
    if cm:
        count = max(1, int(cm.group(1)))
        rest = rest[: cm.start()].strip()
    gift = rest.strip(" ×xX*")
    if not gift and gift_alts:
        gift = gift_alts[-1]  # 礼物图标通常是消息里最后一张带 alt 的图
    if not gift or not user:
        return None
    if len(gift) > 20:
        return None  # 长句大概率是普通弹幕误伤,弃
    return user, gift, count


def parse_chat(text: str):
    """打字弹幕 "昵称:内容" → (user≤20, content≤50);任一为空 → None。"""
    import re

    m = re.match(r"^\s*([^:：]{1,40})[:：]\s*(.+)$", text)
    if not m:
        return None
    user = m.group(1).strip()[:20]
    content = m.group(2).strip()[:50]
    if not user or not content:
        return None
    return user, content


def parse_follow(text: str):
    """"XX 关注了主播" → user;mod 端 LRU 是去重权威,这里只做会话级预去重。"""
    import re

    m = re.match(r"^\s*(.{1,40}?)\s*关注了主播", text)
    if not m:
        return None
    user = m.group(1).strip()[:20]
    return user or None


def post_gift(bridge: str, token: str, user: str, gift: str, count: int, dry_run: bool) -> str:
    if dry_run:
        return "dry-run"
    payload = json.dumps({"user": user, "gift": gift, "count": count}).encode("utf-8")
    req = urllib.request.Request(bridge, data=payload, method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            return f"{resp.status} {resp.read().decode('utf-8', 'replace')}"
    except urllib.error.HTTPError as e:  # 429=桥端冷却/去重,属正常拒绝
        return f"{e.code} {e.read().decode('utf-8', 'replace')}"
    except Exception as e:
        return f"POST失败: {e}"


def post_danmaku(url: str, token: str, items: list[dict], dry_run: bool) -> bool:
    """批量投递弹幕/关注;失败不重发(弹幕保鲜,丢了零成本)。返回是否成功。"""
    if dry_run:
        print(f"[dry] /danmaku ×{len(items)}: " + "; ".join(f"{i['kind']}:{i['user']}:{i.get('text','')}" for i in items))
        return True
    payload = json.dumps({"items": items}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=payload, method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            resp.read()
            return 200 <= resp.status < 300
    except Exception as e:
        print(f"[弹幕] POST失败: {e}")
        return False


def check_bridge(bridge: str):
    health = bridge.rsplit("/", 1)[0] + "/health"
    try:
        with urllib.request.urlopen(health, timeout=2) as resp:
            print(f"[桥] {health} → {resp.status},桥在线")
            return True
    except Exception as e:
        print(f"[桥] ⚠️ {health} 不通({e})——MC 服务端没开?事件会投递失败,监听照常跑")
        return False


def watch(room: str, bridge: str, token: str, headless: bool, dry_run: bool, strict: bool,
          agg_idle: float, agg_max: float, danmaku_on: bool, danmaku_flush: float, danmaku_batch: int):
    from playwright.sync_api import sync_playwright

    url = room if room.startswith("http") else f"https://live.douyin.com/{room}"
    danmaku_url = bridge.rsplit("/", 1)[0] + "/danmaku"
    PROFILE_DIR.mkdir(parents=True, exist_ok=True)
    check_bridge(bridge)

    backoff_steps = [5, 10, 20, 30]
    backoff_index = 0
    with sync_playwright() as p:
        while True:
            session_started = time.time()
            try:
                run_session(p, url, bridge, danmaku_url, token, headless, dry_run, strict,
                            agg_idle, agg_max, danmaku_on, danmaku_flush, danmaku_batch)
                return  # run_session 只有 KeyboardInterrupt 之外的正常退出(不存在)才到这
            except KeyboardInterrupt:
                print("\n[页] 退出")
                return
            except Exception as e:
                # 自愈:浏览器崩溃/页面关闭/evaluate 异常 → 退避后重连重挂(此前这里会直接死掉,
                # 直播中途断流要人工发现+重启——收礼断流是营收事故)。
                if time.time() - session_started > 120:
                    backoff_index = 0  # 会话存活够久,视为恢复正常,退避复位
                delay = backoff_steps[min(backoff_index, len(backoff_steps) - 1)]
                backoff_index += 1
                print(f"[自愈] 会话异常({type(e).__name__}: {e}),{delay}s 后重连…")
                time.sleep(delay)


def run_session(p, url: str, bridge: str, danmaku_url: str, token: str, headless: bool,
                dry_run: bool, strict: bool, agg_idle: float, agg_max: float,
                danmaku_on: bool, danmaku_flush: float, danmaku_batch: int):
    """单次浏览器会话:launch → goto(3 次重试) → 注入 → 主循环。异常上抛给 watch() 自愈重连。"""
    recent: dict[str, float] = {}  # Python 侧 1.2s 去重(仅 --agg-idle 0 直发模式下兜底)
    # 连击聚合池:(user,gift) -> {total, max_seen, first, last}。
    # 不变式:agg_idle(默认2.0s) 必须大于 mod 端 dedupMs(默认1.5s)——两个合法批次的间隔
    # 天然 ≥ agg_idle,永不被 mod 去重误杀;同批次的意外重发落在 dedup 窗内被正确拦截。
    pending: dict[tuple[str, str], dict] = {}
    danmaku_pending: list[dict] = []
    seen_follows: set[str] = set()
    stats = {"gift": 0, "chat": 0, "follow": 0, "post_ok": 0, "post_fail": 0}
    post_fail_streak = [0]

    def send(user: str, gift: str, count: int):
        result = post_gift(bridge, token, user, gift, count, dry_run)
        stamp = time.strftime("%H:%M:%S")
        print(f"[{stamp}] 🎁 {user} 送出 {gift} ×{count} → {result}")
        if result.startswith("POST失败"):
            stats["post_fail"] += 1
            post_fail_streak[0] += 1
        else:
            stats["post_ok"] += 1
            post_fail_streak[0] = 0

    def on_event(payload):
        kind = payload.get("kind") or "gift"  # 缺 kind 按 gift 兼容(旧观察器残留)
        text = payload.get("text", "")
        if kind == "gift":
            parsed = parse_gift(text, payload.get("alts", []), strict)
            if not parsed:
                return
            stats["gift"] += 1
            user, gift, count = parsed
            now = time.time()
            if agg_idle <= 0:  # 关聚合:回退直发(校准/调试用)
                sig = f"{user}|{gift}|{count}"
                if now - recent.get(sig, 0) < 1.2:
                    return
                recent[sig] = now
                send(user, gift, count)
                return
            entry = pending.get((user, gift))
            if entry is None:
                pending[(user, gift)] = {"total": count, "max_seen": count, "first": now, "last": now}
                return
            # 抖音连击两种形态都要算对:累计计数(×1→×2→×3 逐条刷,真实总数=最新值)只加增量;
            # 独立单发(N 条 ×1)逐条累加。判据:计数比见过的最大值还大 = 累计计数在涨。
            if count > entry["max_seen"]:
                entry["total"] += count - entry["max_seen"]
                entry["max_seen"] = count
            else:
                entry["total"] += count
            entry["last"] = now
            return
        if not danmaku_on:
            return
        if kind == "chat":
            parsed = parse_chat(text)
            if not parsed:
                return
            stats["chat"] += 1
            user, content = parsed
            danmaku_pending.append({"kind": "chat", "user": user, "text": content})
            return
        if kind == "follow":
            user = parse_follow(text)
            if not user or user in seen_follows:
                return
            seen_follows.add(user)
            stats["follow"] += 1
            danmaku_pending.append({"kind": "follow", "user": user, "text": ""})

    def flush_pending():
        now = time.time()
        for key in list(pending.keys()):
            entry = pending[key]
            # 静默 agg_idle 秒 = 连击结束;或存活满 agg_max 秒强制出账,连击不断流也不会无限攒。
            if now - entry["last"] >= agg_idle or now - entry["first"] >= agg_max:
                del pending[key]
                send(key[0], key[1], entry["total"])

    def flush_danmaku():
        if not danmaku_pending:
            return
        batch = danmaku_pending[:danmaku_batch]
        del danmaku_pending[:len(batch)]
        if post_danmaku(danmaku_url, token, batch, dry_run):
            stats["post_ok"] += 1
            post_fail_streak[0] = 0
        else:
            stats["post_fail"] += 1
            post_fail_streak[0] += 1

    context = p.chromium.launch_persistent_context(
        str(PROFILE_DIR),
        headless=headless,
        viewport={"width": 1280, "height": 860},
        args=["--disable-blink-features=AutomationControlled"],
    )
    try:
        page = context.pages[0] if context.pages else context.new_page()
        page.expose_function("__aibotGift", on_event)
        print(f"[页] 打开 {url} …(首次可能要人工登录/过滑块)")
        goto_error = None
        for attempt in range(3):
            try:
                page.goto(url, wait_until="domcontentloaded", timeout=60_000)
                goto_error = None
                break
            except Exception as e:
                goto_error = e
                print(f"[页] goto 失败({attempt + 1}/3): {e}")
                time.sleep(5)
        if goto_error is not None:
            raise goto_error
        page.wait_for_timeout(3_000)
        page.evaluate(OBSERVER_JS)
        print(f"[页] 监听器已注入(礼物聚合 idle={agg_idle}s max={agg_max}s,"
              f"弹幕={'开' if danmaku_on else '关'})。Ctrl+C 退出。")
        reinject_at = time.time() + 5.0
        danmaku_at = time.time() + danmaku_flush
        heartbeat_at = time.time() + 60.0
        while True:
            if page.is_closed():
                raise RuntimeError("page_closed")
            page.wait_for_timeout(1_000)  # 1s 一轮,聚合池及时出账
            now = time.time()
            if agg_idle > 0:
                flush_pending()
            if danmaku_on and now >= danmaku_at:
                flush_danmaku()
                danmaku_at = now + danmaku_flush
            # 页面被导航(如跳登录页再回来)会丢注入,每 5s 幂等补挂
            if now >= reinject_at:
                page.evaluate(OBSERVER_JS)
                reinject_at = now + 5.0
            if now >= heartbeat_at:
                heartbeat_at = now + 60.0
                print(f"[心跳] gifts={stats['gift']} chat={stats['chat']} follow={stats['follow']} "
                      f"post_ok={stats['post_ok']} post_fail={stats['post_fail']}")
                check_bridge(bridge)
                if post_fail_streak[0] >= 5:
                    print("[心跳] 🚨 连续 POST 失败 ≥5——桥/服务端多半挂了,请检查 MC 服务端!")
                try:
                    if "login" in (page.url or ""):
                        print("[心跳] ⚠️ 页面疑似跳到登录页——登录态失效,重跑 find_room.py 扫码后重启本脚本")
                except Exception:
                    pass
    finally:
        try:
            context.close()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser(description="抖音直播事件监听 → aibot 桥(礼物/弹幕/关注)")
    ap.add_argument("room", nargs="?", help="房间号或完整直播间 URL")
    ap.add_argument("--bridge", default="http://127.0.0.1:8787/gift")
    ap.add_argument("--token", default="")
    ap.add_argument("--headless", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--lax", action="store_true",
                    help="关闭'必须带礼物图标'防伪判(仅开播首测配 --dry-run 校准用)")
    ap.add_argument("--test", metavar="礼物名", help="直接向桥发一发测试礼物后退出")
    ap.add_argument("--user", default="测试观众")
    ap.add_argument("--count", type=int, default=1)
    ap.add_argument("--agg-idle", type=float, default=2.0,
                    help="连击聚合:同 (user,礼物) 静默 N 秒后出账(默认2.0,须>mod dedupMs 1.5s;0=关聚合直发)")
    ap.add_argument("--agg-max", type=float, default=6.0,
                    help="连击聚合:单批最长攒 N 秒强制出账(默认6.0)")
    ap.add_argument("--no-danmaku", action="store_true", help="只采集礼物(回退旧行为)")
    ap.add_argument("--danmaku-flush", type=float, default=2.0, help="弹幕批量间隔秒(默认2.0)")
    ap.add_argument("--danmaku-batch", type=int, default=10, help="单批弹幕上限(默认10)")
    args = ap.parse_args()

    if args.test:
        print(post_gift(args.bridge, args.token, args.user, args.test, args.count, False))
        return
    if not args.room:
        ap.error("缺房间号(或用 --test 礼物名 做桥连通测试)")
    watch(args.room, args.bridge, args.token, args.headless, args.dry_run, not args.lax,
          args.agg_idle, args.agg_max, not args.no_danmaku,
          max(0.5, args.danmaku_flush), max(1, args.danmaku_batch))


if __name__ == "__main__":
    main()
