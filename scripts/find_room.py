#!/usr/bin/env python3
"""一次性找出自己抖音直播间的房间号(web_rid)

背景:分享短链(v.douyin.com/xxx)解析出来的是内部 room_id(webcast.amemv.com/reflow/...),
网页版直播间 live.douyin.com 用的是另一个号 web_rid,匿名爬不到(登录墙+反爬),
但登录后自己主页的"直播中"头像角标就挂着它。

用法:
  python3 scripts/find_room.py            # 弹浏览器窗口,没登录就扫码,自动打印房间号
  python3 scripts/find_room.py --headless # 登录态存好后可无头重跑

登录态与 douyin_gift_watch.py 共用(~/.aibot/douyin-profile),这里扫一次码,
礼物监听那边就不用再登了。

注意:必须**开播状态**下跑,下播了主页没有直播入口,找不到属正常。
房间号一般固定,找到一次记下来就行。
"""

import argparse
import re
import time
import urllib.parse
from pathlib import Path

PROFILE_DIR = Path.home() / ".aibot" / "douyin-profile"
# 主播本人的 sec_uid(从直播分享链接的 sec_user_id 参数拿到,身份 ID 不变)
SEC_UID = "MS4wLjABAAAARNQuZknhn7roqHDZyTo9BnmILnnLVLcN5FSGDntgkUIEjwNtI0rhxbeQlxP-VT0Y"
LOGIN_WAIT_SECONDS = 300


def logged_in(context) -> bool:
    for cookie in context.cookies("https://www.douyin.com"):
        if cookie["name"] in ("sessionid", "sessionid_ss") and cookie["value"]:
            return True
    return False


def extract_rids(html: str) -> set:
    html = urllib.parse.unquote(html)
    rids = set(re.findall(r"live\.douyin\.com/(\d{8,})", html))
    rids |= set(re.findall(r"(?:webRid|web_rid)[\"':\s\\]+(\d{8,})", html))
    return rids


def main():
    ap = argparse.ArgumentParser(description="找自己抖音直播间的房间号(web_rid)")
    ap.add_argument("--sec-uid", default=SEC_UID, help="主播 sec_uid(默认已内置)")
    ap.add_argument("--headless", action="store_true", help="登录态存好后可无头跑")
    args = ap.parse_args()

    from playwright.sync_api import sync_playwright

    PROFILE_DIR.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        context = p.chromium.launch_persistent_context(
            str(PROFILE_DIR),
            headless=args.headless,
            viewport={"width": 1280, "height": 860},
            args=["--disable-blink-features=AutomationControlled"],
        )
        page = context.pages[0] if context.pages else context.new_page()
        profile_url = f"https://www.douyin.com/user/{args.sec_uid}"
        print(f"[1/4] 打开主页 {profile_url}")
        page.goto(profile_url, wait_until="domcontentloaded", timeout=60_000)
        page.wait_for_timeout(4_000)

        if not logged_in(context):
            if args.headless:
                print("❌ 还没登录过,不能用 --headless。先不带参数跑一次扫码。")
                context.close()
                return
            print(f"[2/4] 未登录 → 请在弹出的浏览器窗口里扫码登录(等 {LOGIN_WAIT_SECONDS}s)…")
            deadline = time.time() + LOGIN_WAIT_SECONDS
            while time.time() < deadline and not logged_in(context):
                page.wait_for_timeout(2_000)
            if not logged_in(context):
                print("❌ 等登录超时,重跑一次再扫。")
                context.close()
                return
            print("      ✅ 登录成功(登录态已存,礼物监听脚本以后也不用再登)")
            page.goto(profile_url, wait_until="domcontentloaded", timeout=60_000)
            page.wait_for_timeout(4_000)
        else:
            print("[2/4] 已有登录态,跳过扫码")

        title = page.title()
        nickname = title.split("的抖音")[0].strip() if "的抖音" in title else ""
        print(f"[3/4] 主页昵称: {nickname or '(没解析到)'}")

        # 候选来源 1:页面里所有 live.douyin.com 链接(含头像"直播中"角标)
        candidates = set()
        for a in page.locator('a[href*="live.douyin.com/"]').all():
            href = a.get_attribute("href") or ""
            m = re.search(r"live\.douyin\.com/(\d{8,})", href)
            if m:
                candidates.add(m.group(1))
        # 候选来源 2:SSR 数据全文正则(可能混进页面推广位,后面逐个验证)
        candidates |= extract_rids(page.content())

        if not candidates:
            print("❌ 主页上没有任何直播间入口。最常见原因:**现在没开播**。")
            print("   开播后再跑一次(登录态已存,几秒出结果)。")
            context.close()
            return

        print(f"[4/4] 候选 {sorted(candidates)},逐个打开验证标题是否属于「{nickname}」…")
        confirmed = None
        for rid in sorted(candidates):
            page.goto(f"https://live.douyin.com/{rid}", wait_until="domcontentloaded", timeout=60_000)
            page.wait_for_timeout(4_000)
            room_title = page.title()
            owner = room_title.split("的抖音直播间")[0].strip() if "的抖音直播间" in room_title else ""
            match = bool(nickname) and owner == nickname
            print(f"      {rid} → 「{room_title[:40]}」 {'✅ 就是你' if match else '✗ 不是'}")
            if match:
                confirmed = rid
                break

        print()
        if confirmed:
            print(f"🎯 你的房间号: {confirmed}")
            print(f"   直播间地址: https://live.douyin.com/{confirmed}")
            print("   房间号一般固定,记下来。开播后启动礼物监听:")
            print(f"   python3 scripts/douyin_gift_watch.py {confirmed} --dry-run   # 首播先彩排")
            print(f"   python3 scripts/douyin_gift_watch.py {confirmed} --headless  # 校准后正式跑")
        else:
            print("❌ 候选都不匹配。可能没开播(直播入口不渲染),开播后再跑一次。")
        context.close()


if __name__ == "__main__":
    main()
