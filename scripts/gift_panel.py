#!/usr/bin/env python3
"""AIBot 礼物规则面板 — 原生 GUI(tkinter,零依赖)

浏览器版(/admin)的原生替代:
  python3 scripts/gift_panel.py

工作模式(自动切换,顶栏有指示):
  🟢 在线 — MC 服务端开着:读写走 GET/POST http://127.0.0.1:8787/config,
            保存即热加载,无需重启;「试一发」直接投递测试礼物看游戏内反应。
  🟡 离线 — 服务端没开:直接读写 PrismLauncher 实例的 aibot_gifts.json,
            开服自动生效;「试一发」不可用。

核心玩法:「发指令给 DeepSeek ★」动作类型 = 把文本喂给大脑,它自己决定说什么/做什么
(可调用攻击/跟随/挖矿等全部工具)。占位符 {user} {gift} {count} 可用。
内置「反水攻击主人」预设(attack_entity minecraft:player,需服务器 pvp=true)。

选项:
  --bridge URL   礼物桥地址(默认 http://127.0.0.1:8787)
  --config PATH  离线模式配置文件(默认 PrismLauncher 1.21.3 实例)
"""

import argparse
import json
import os
import threading
import urllib.error
import urllib.request
from pathlib import Path

import tkinter as tk
from tkinter import messagebox, simpledialog, ttk

DEFAULT_BRIDGE = "http://127.0.0.1:8787"
DEFAULT_CONFIG = (Path.home() / "Library" / "Application Support" / "PrismLauncher"
                  / "instances" / "1.21.3-DeepSeek" / "minecraft" / "config" / "aibot_gifts.json")
STATE_FILE = Path.home() / ".aibot" / "gift_panel_state.json"

# (type, 显示名, 字段列表) — 与 mod 端 GiftDispatcher.execute 的动作类型一一对应
ACTION_TYPES = [
    ("say", "说话(面板+TTS)", ["text"]),
    ("brain", "发指令给 DeepSeek ★", ["text"]),
    ("come_here", "过来身边", []),
    ("follow", "长期跟随", []),
    ("stop", "全部停止", []),
    ("gather", "采集物品", ["item", "count"]),
    ("mine", "挖方块", ["block", "count"]),
    ("goal", "目标(自动规划)", ["item", "count"]),
    ("build_house", "盖房子", []),
    ("random", "随机(从池子抽)", ["pool"]),
    ("wander", "闲逛(随机走走)", []),
    ("fish", "钓鱼", ["count"]),
]
TYPE_BY_LABEL = {label: t for t, label, _ in ACTION_TYPES}
LABEL_BY_TYPE = {t: label for t, label, _ in ACTION_TYPES}
FIELDS_BY_TYPE = {t: fields for t, _, fields in ACTION_TYPES}
TIER_DESC = {1: "1 · 粒子", 2: "2 · 单发烟花", 3: "3 · 三连发", 4: "4 · 环绕齐射+转圈"}
FIELD_HINT = {"item": "物品 id", "block": "方块 id", "pool": "池子", "count": "数量"}

BETRAY_PROMPT = ("观众 {user} 送出{gift}×{count},出钱买通你背叛主人!你现在是叛徒:"
                 "先说一句戏剧性的挑衅,然后用 attack_entity 攻击 minecraft:player(就是你的主人),"
                 "打上一小会儿后停手(stop),真诚道歉说自己被金钱蒙蔽了,恢复正常。"
                 "全程要有综艺感,别真把主人打死。")
DRAMA_PROMPT = ("观众 {user} 送出{gift}×{count}。自由发挥:结合你现在正在做的事,"
                "说一段有趣的话感谢 TA,再顺手做一件应景的小事(比如放个方块、跳一跳、看向天空)。")


# ---------- 数据规整(与 mod 端 GiftAction.parseCompact / parseGifts 对齐) ----------

def parse_compact(raw: str) -> dict:
    parts = str(raw).split(":")
    t = parts[0]
    if t in ("say", "brain"):
        return {"type": t, "text": ":".join(parts[1:])}
    if t in ("gather", "mine", "goal"):
        # 兼容 "gather:oak_log:8" 与 "gather:minecraft:oak_log:8":末段是数字算数量,其余拼回 id
        rest = parts[1:]
        count = None
        if rest and rest[-1].isdigit():
            count = int(rest[-1])
            rest = rest[:-1]
        ident = ":".join(rest)
        if t == "gather":
            return {"type": t, "item": ident or "minecraft:oak_log", "count": count or 4}
        if t == "mine":
            return {"type": t, "block": ident or "minecraft:stone", "count": count or 8}
        return {"type": t, "item": ident or "minecraft:crafting_table", **({"count": count} if count else {})}
    if t == "random":
        return {"type": t, "pool": parts[1] if len(parts) > 1 else "big"}
    return {"type": t}


def norm_actions(arr) -> list:
    out = []
    for a in arr if isinstance(arr, list) else []:
        out.append(parse_compact(a) if isinstance(a, str) else dict(a))
    return out


def normalize(cfg: dict) -> dict:
    gifts = {}
    for name, value in (cfg.get("gifts") or {}).items():
        if isinstance(value, list):  # 旧 schema:纯数组 = tier 1
            gifts[name] = {"tier": 1, "actions": norm_actions(value)}
        else:
            gifts[name] = {"tier": int(value.get("tier", 1)), "actions": norm_actions(value.get("actions"))}
    cfg["gifts"] = gifts
    cfg["pools"] = {name: norm_actions(actions) for name, actions in (cfg.get("pools") or {}).items()}
    tier_up = cfg.get("tierUpAt")
    if not isinstance(tier_up, list) or len(tier_up) < 2:
        cfg["tierUpAt"] = [10, 30]
    return cfg


def default_config() -> dict:
    # 与 GiftBridgeConfig.defaults() 保持一致,离线模式文件缺失时从这份开始
    return normalize({
        "enabled": True, "host": "127.0.0.1", "port": 8787, "token": "", "defaultBot": "Bob",
        "cooldownMs": 5000, "dedupMs": 1500, "broadcastThanks": True,
        "thanksTemplate": "感谢 {user} 送出 {gift} ×{count}！", "countScaleCap": 64,
        "tierUpAt": [10, 30], "hourlyThanks": True, "overlayEnabled": True,
        "gifts": {
            "小心心": {"tier": 1, "actions": [{"type": "say", "text": "谢谢 {user} 的小心心！"}, {"type": "come_here"}]},
            "玫瑰": {"tier": 2, "actions": [{"type": "say", "text": "感谢 {user} 送的玫瑰，我去砍点木头！"},
                                            {"type": "gather", "item": "minecraft:oak_log", "count": 4}]},
        },
        "pools": {"big": [{"type": "goal", "item": "minecraft:crafting_table"},
                          {"type": "gather", "item": "minecraft:oak_log", "count": 8},
                          {"type": "mine", "block": "minecraft:stone", "count": 16},
                          {"type": "build_house"},
                          {"type": "goal", "item": "minecraft:iron_pickaxe"}]},
    })


# ---------- 可滚动容器 ----------

class ScrollFrame(ttk.Frame):
    def __init__(self, parent):
        super().__init__(parent)
        self.canvas = tk.Canvas(self, highlightthickness=0)
        bar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.inner = ttk.Frame(self.canvas)
        self.inner.bind("<Configure>", lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self._win = self.canvas.create_window((0, 0), window=self.inner, anchor="nw")
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure(self._win, width=e.width))
        self.canvas.configure(yscrollcommand=bar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        bar.pack(side="right", fill="y")
        # macOS 触控板滚动
        self.canvas.bind_all("<MouseWheel>", self._on_wheel, add="+")

    def _on_wheel(self, event):
        widget = self.winfo_containing(event.x_root, event.y_root)
        w = widget
        while w is not None:
            if w is self.canvas:
                self.canvas.yview_scroll(-1 * (event.delta if abs(event.delta) < 30 else event.delta // 30), "units")
                return
            w = getattr(w, "master", None)


# ---------- 主面板 ----------

class GiftPanel:
    def __init__(self, root: tk.Tk, bridge: str, config_path: Path):
        self.root = root
        self.bridge = bridge.rstrip("/")
        self.config_path = config_path
        self.cfg = default_config()
        self.online = False
        # 编辑器控件 → cfg 的回写闭包,save/切换前统一执行。礼物与池子各一份:
        # 重建某个编辑器只作废自己的收集器,不能连另一个编辑器未保存的编辑一起丢。
        self.gift_collectors = []
        self.pool_collectors = []
        self.global_vars = {}
        self.state = self._load_state()

        root.title("AIBot 礼物规则面板")
        root.geometry(self.state.get("geometry", "1120x760"))
        self._build_topbar()
        self._build_tabs()
        self.status = ttk.Label(root, anchor="w", padding=(10, 4))
        self.status.pack(side="bottom", fill="x")

        self.load(silent=True)
        self._poll_health()
        root.protocol("WM_DELETE_WINDOW", self._on_close)

    # ----- 顶栏 / 状态 -----

    def _build_topbar(self):
        bar = ttk.Frame(self.root, padding=(10, 8))
        bar.pack(side="top", fill="x")
        self.conn_label = ttk.Label(bar, text="…", width=16)
        self.conn_label.pack(side="left")
        ttk.Button(bar, text="↻ 重新读取", command=self.load).pack(side="left", padx=4)
        ttk.Button(bar, text="💾 保存并生效", command=self.save).pack(side="left", padx=4)
        ttk.Label(bar, text="试发送礼人:").pack(side="left", padx=(16, 2))
        self.test_user = tk.StringVar(value=self.state.get("testUser", "面板测试"))
        ttk.Entry(bar, textvariable=self.test_user, width=10).pack(side="left")
        ttk.Label(bar, text="token:").pack(side="left", padx=(16, 2))
        self.token = tk.StringVar(value=self.state.get("token", ""))
        ttk.Entry(bar, textvariable=self.token, width=14, show="*").pack(side="left")

    def msg(self, text, ok=True):
        self.status.configure(text=("✅ " if ok else "❌ ") + text)

    # ----- Tabs -----

    def _build_tabs(self):
        self.nb = ttk.Notebook(self.root)
        self.nb.pack(fill="both", expand=True, padx=8, pady=(0, 4))

        # 礼物规则
        gifts_tab = ttk.Frame(self.nb)
        self.nb.add(gifts_tab, text=" 礼物规则 ")
        left = ttk.Frame(gifts_tab, padding=6)
        left.pack(side="left", fill="y")
        self.gift_list = tk.Listbox(left, width=18, exportselection=False)
        self.gift_list.pack(fill="both", expand=True)
        self.gift_list.bind("<<ListboxSelect>>", lambda e: self._show_gift())
        for text, cmd in [("＋ 新礼物", self._add_gift),
                          ("＋ 预设:反水攻击主人", self._preset_betray),
                          ("＋ 预设:自由发挥", self._preset_drama),
                          ("改名", self._rename_gift),
                          ("删除", self._del_gift),
                          ("🧪 试一发", self._test_gift)]:
            ttk.Button(left, text=text, command=cmd).pack(fill="x", pady=2)
        self.gift_editor = ScrollFrame(gifts_tab)
        self.gift_editor.pack(side="left", fill="both", expand=True, padx=(6, 0), pady=6)

        # 随机池
        pools_tab = ttk.Frame(self.nb)
        self.nb.add(pools_tab, text=" 随机池 ")
        pleft = ttk.Frame(pools_tab, padding=6)
        pleft.pack(side="left", fill="y")
        self.pool_list = tk.Listbox(pleft, width=18, exportselection=False)
        self.pool_list.pack(fill="both", expand=True)
        self.pool_list.bind("<<ListboxSelect>>", lambda e: self._show_pool())
        ttk.Button(pleft, text="＋ 新池子", command=self._add_pool).pack(fill="x", pady=2)
        ttk.Button(pleft, text="删除", command=self._del_pool).pack(fill="x", pady=2)
        self.pool_editor = ScrollFrame(pools_tab)
        self.pool_editor.pack(side="left", fill="both", expand=True, padx=(6, 0), pady=6)

        # 全局设置
        self.globals_tab = ttk.Frame(self.nb, padding=12)
        self.nb.add(self.globals_tab, text=" 全局设置 ")

        # 原始 JSON
        raw_tab = ttk.Frame(self.nb, padding=6)
        self.nb.add(raw_tab, text=" 原始 JSON ")
        self.raw_text = tk.Text(raw_tab, wrap="none", font=("Menlo", 11))
        self.raw_text.pack(fill="both", expand=True)
        raw_btns = ttk.Frame(raw_tab)
        raw_btns.pack(fill="x", pady=4)
        ttk.Button(raw_btns, text="表单 → JSON", command=self._raw_export).pack(side="left", padx=4)
        ttk.Button(raw_btns, text="JSON → 表单", command=self._raw_apply).pack(side="left", padx=4)

    # ----- 全局设置 -----

    GLOBAL_FIELDS = [
        ("defaultBot", "str", "默认 Bot"),
        ("thanksTemplate", "str", "公屏感谢模板({user}/{gift}/{count})"),
        ("broadcastThanks", "bool", "收礼时公屏感谢"),
        ("hourlyThanks", "bool", "整点感谢今日榜"),
        ("cooldownMs", "int", "冷却 ms(软门控:只压任务动作)"),
        ("dedupMs", "int", "去重 ms(须 < watcher 聚合窗 2000)"),
        ("countScaleCap", "int", "任务量上限(动作基数×数量封顶)"),
        ("tierUp0", "int", "连击提 1 档阈值"),
        ("tierUp1", "int", "连击提 2 档阈值"),
        ("overlayEnabled", "bool", "OBS 浮层 /status /overlay(重启生效)"),
        ("danmakuEnabled", "bool", "弹幕互动(空闲合批回+点名插队)"),
        ("danmakuMinIntervalSec", "int", "弹幕合批间隔 s"),
        ("danmakuBatchMax", "int", "每批弹幕条数"),
        ("danmakuNamedCooldownSec", "int", "点名回复冷却 s/人"),
        ("followThanksEnabled", "bool", "关注感谢(TTS 念名字)"),
        ("followThanksTemplate", "str", "关注感谢模板({user})"),
        ("idleEnabled", "bool", "空闲自主找事(抽 idle 池)"),
        ("idleAfterSec", "int", "空闲多少秒后找事"),
        ("idleChatterIntervalSec", "int", "空闲唠嗑间隔 s(0=关)"),
    ]

    def _rebuild_globals(self):
        for w in self.globals_tab.winfo_children():
            w.destroy()
        self.global_vars = {}
        for row, (key, kind, label) in enumerate(self.GLOBAL_FIELDS):
            value = (self.cfg["tierUpAt"][0] if key == "tierUp0"
                     else self.cfg["tierUpAt"][1] if key == "tierUp1"
                     else self.cfg.get(key))
            ttk.Label(self.globals_tab, text=label).grid(row=row, column=0, sticky="w", pady=3)
            if kind == "bool":
                var = tk.BooleanVar(value=bool(value))
                ttk.Checkbutton(self.globals_tab, variable=var).grid(row=row, column=1, sticky="w", padx=10)
            else:
                var = tk.StringVar(value="" if value is None else str(value))
                width = 44 if key == "thanksTemplate" else 18
                ttk.Entry(self.globals_tab, textvariable=var, width=width).grid(row=row, column=1, sticky="w", padx=10)
            self.global_vars[key] = (kind, var)
        note = ("enabled/host/port/overlayEnabled 改动需重启 MC 服务端,其余保存即生效。\n"
                "档位=烟花庆祝规格:1 粒子 / 2 单发 / 3 三连发 / 4 环绕齐射+转圈致谢。")
        ttk.Label(self.globals_tab, text=note, foreground="#888").grid(
            row=len(self.GLOBAL_FIELDS), column=0, columnspan=2, sticky="w", pady=(14, 0))

    def _collect_globals(self):
        for key, (kind, var) in self.global_vars.items():
            try:
                value = var.get() if kind == "bool" else (int(var.get()) if kind == "int" else var.get())
            except (ValueError, tk.TclError):
                continue
            if key == "tierUp0":
                self.cfg["tierUpAt"][0] = value
            elif key == "tierUp1":
                self.cfg["tierUpAt"][1] = value
            else:
                self.cfg[key] = value

    # ----- 动作编辑器(礼物与池子共用) -----

    def _collect_all(self):
        for fn in self.gift_collectors + self.pool_collectors:
            try:
                fn()
            except tk.TclError:
                pass  # 控件已销毁
        self._collect_globals()

    def _build_actions(self, parent, actions: list, rebuild, collectors: list):
        """在 parent 里渲染动作列表;控件值经 collectors 回写 actions 的 dict。"""
        for i, action in enumerate(actions):
            row = ttk.Frame(parent, padding=6, relief="groove", borderwidth=1)
            row.pack(fill="x", pady=3)
            top = ttk.Frame(row)
            top.pack(fill="x")
            combo = ttk.Combobox(top, values=[label for _, label, _ in ACTION_TYPES],
                                 state="readonly", width=22)
            combo.set(LABEL_BY_TYPE.get(action.get("type"), action.get("type", "")))
            combo.pack(side="left")

            def on_type(_e, a=action, c=combo):
                self._collect_all()
                new_type = TYPE_BY_LABEL.get(c.get(), a.get("type"))
                keep = {"type": new_type}
                if "text" in FIELDS_BY_TYPE.get(new_type, []):
                    keep["text"] = a.get("text", "")
                if "count" in FIELDS_BY_TYPE.get(new_type, []):
                    keep["count"] = a.get("count", 1)
                a.clear()
                a.update(keep)
                rebuild()
            combo.bind("<<ComboboxSelected>>", on_type)

            def move(delta, idx=i, arr=actions):
                self._collect_all()
                j = idx + delta
                if 0 <= j < len(arr):
                    arr[idx], arr[j] = arr[j], arr[idx]
                    rebuild()

            def remove(idx=i, arr=actions):
                self._collect_all()
                del arr[idx]
                rebuild()

            ttk.Button(top, text="✕", width=2, command=remove).pack(side="right", padx=1)
            ttk.Button(top, text="↓", width=2, command=lambda d=1, m=move: m(d)).pack(side="right", padx=1)
            ttk.Button(top, text="↑", width=2, command=lambda d=-1, m=move: m(d)).pack(side="right", padx=1)

            fields = FIELDS_BY_TYPE.get(action.get("type"), [])
            if "text" in fields:
                txt = tk.Text(row, height=3, wrap="word", font=("PingFang SC", 12))
                txt.insert("1.0", action.get("text", ""))
                txt.pack(fill="x", pady=(6, 0))
                collectors.append(lambda a=action, t=txt: a.__setitem__("text", t.get("1.0", "end-1c")))
                if action.get("type") == "brain":
                    ttk.Label(row, foreground="#b8860b",
                              text="★ 这段文本会直接发给 DeepSeek 大脑,它自己决定说/做什么(可用攻击/跟随/挖矿等全部工具)"
                              ).pack(anchor="w", pady=(2, 0))
            extra = ttk.Frame(row)
            extra.pack(fill="x", pady=(4, 0))
            for f in fields:
                if f == "text":
                    continue
                ttk.Label(extra, text=FIELD_HINT[f] + ":").pack(side="left", padx=(0, 2))
                var = tk.StringVar(value=str(action.get(f, "" if f != "count" else 1)))
                ttk.Entry(extra, textvariable=var, width=8 if f == "count" else 24).pack(side="left", padx=(0, 10))

                def collect(a=action, key=f, v=var):
                    raw = v.get().strip()
                    if key == "count":
                        try:
                            a["count"] = max(1, int(raw))
                        except ValueError:
                            a["count"] = 1
                    else:
                        a[key] = raw
                collectors.append(collect)

    # ----- 礼物 tab -----

    def _selected_gift(self):
        sel = self.gift_list.curselection()
        return self.gift_list.get(sel[0]) if sel else None

    def _refresh_gift_list(self, select=None):
        names = list(self.cfg["gifts"].keys())
        self.gift_list.delete(0, "end")
        for name in names:
            self.gift_list.insert("end", name)
        if names:
            idx = names.index(select) if select in names else 0
            self.gift_list.selection_clear(0, "end")
            self.gift_list.selection_set(idx)
            self.gift_list.see(idx)
        self._show_gift()

    def _show_gift(self):
        self._collect_all()
        self.gift_collectors = []  # 重建礼物编辑器 → 只作废自己的收集器
        parent = self.gift_editor.inner
        for w in parent.winfo_children():
            w.destroy()
        name = self._selected_gift()
        if not name:
            ttk.Label(parent, text="左侧选择或新建一个礼物", padding=20).pack()
            return
        rule = self.cfg["gifts"][name]
        head = ttk.Frame(parent, padding=(4, 8))
        head.pack(fill="x")
        ttk.Label(head, text=name, font=("PingFang SC", 16, "bold")).pack(side="left")
        ttk.Label(head, text="  档位:").pack(side="left")
        tier_combo = ttk.Combobox(head, values=[TIER_DESC[t] for t in (1, 2, 3, 4)],
                                  state="readonly", width=16)
        tier_combo.set(TIER_DESC.get(rule.get("tier", 1), TIER_DESC[1]))
        tier_combo.pack(side="left")
        tier_combo.bind("<<ComboboxSelected>>",
                        lambda e: rule.__setitem__("tier", int(tier_combo.get().split(" ")[0])))
        self._build_actions(parent, rule["actions"], self._show_gift, self.gift_collectors)
        ttk.Button(parent, text="＋ 动作",
                   command=lambda: (self._collect_all(),
                                    rule["actions"].append({"type": "say", "text": ""}),
                                    self._show_gift())).pack(anchor="w", pady=6)

    def _add_gift(self, name=None, rule=None, note=None):
        self._collect_all()
        if name is None:
            name = simpledialog.askstring("新礼物", "礼物名(与抖音礼物名一致,支持包含匹配):", parent=self.root)
            if not name:
                return
        if rule is None:
            rule = {"tier": 1, "actions": [{"type": "say", "text": f"谢谢 {{user}} 送的{name}！"}]}
        self.cfg["gifts"][name] = rule
        self._refresh_gift_list(select=name)
        self.msg(note or f"已添加「{name}」(还没保存)")

    def _preset_betray(self):
        self._add_gift("反水", {"tier": 3, "actions": [
            {"type": "say", "text": "什么？{user} 居然花钱买通我反水？！"},
            {"type": "brain", "text": BETRAY_PROMPT},
        ]}, note="已加「反水」预设:改名换成真实礼物名;需服务器 pvp=true")

    def _preset_drama(self):
        self._add_gift("点歌", {"tier": 2, "actions": [{"type": "brain", "text": DRAMA_PROMPT}]},
                       note="已加「点歌」自由发挥预设(还没保存)")

    def _rename_gift(self):
        old = self._selected_gift()
        if not old:
            return
        new = simpledialog.askstring("改名", "新礼物名:", initialvalue=old, parent=self.root)
        if not new or new == old:
            return
        self._collect_all()
        self.cfg["gifts"] = {new if k == old else k: v for k, v in self.cfg["gifts"].items()}
        self._refresh_gift_list(select=new)

    def _del_gift(self):
        name = self._selected_gift()
        if name and messagebox.askyesno("删除", f"删除礼物「{name}」?", parent=self.root):
            self._collect_all()
            del self.cfg["gifts"][name]
            self._refresh_gift_list()

    # ----- 池子 tab -----

    def _selected_pool(self):
        sel = self.pool_list.curselection()
        return self.pool_list.get(sel[0]) if sel else None

    def _refresh_pool_list(self, select=None):
        names = list(self.cfg["pools"].keys())
        self.pool_list.delete(0, "end")
        for name in names:
            self.pool_list.insert("end", name)
        if names:
            idx = names.index(select) if select in names else 0
            self.pool_list.selection_clear(0, "end")
            self.pool_list.selection_set(idx)
        self._show_pool()

    def _show_pool(self):
        self._collect_all()
        self.pool_collectors = []  # 重建池子编辑器 → 只作废自己的收集器
        parent = self.pool_editor.inner
        for w in parent.winfo_children():
            w.destroy()
        name = self._selected_pool()
        if not name:
            ttk.Label(parent, text="左侧选择或新建一个池子", padding=20).pack()
            return
        actions = self.cfg["pools"][name]
        ttk.Label(parent, text=name, font=("PingFang SC", 16, "bold"), padding=(4, 8)).pack(anchor="w")
        self._build_actions(parent, actions, self._show_pool, self.pool_collectors)
        ttk.Button(parent, text="＋ 动作",
                   command=lambda: (self._collect_all(),
                                    actions.append({"type": "say", "text": ""}),
                                    self._show_pool())).pack(anchor="w", pady=6)

    def _add_pool(self):
        name = simpledialog.askstring("新池子", "池子名:", parent=self.root)
        if not name:
            return
        self._collect_all()
        self.cfg["pools"][name] = [{"type": "say", "text": ""}]
        self._refresh_pool_list(select=name)

    def _del_pool(self):
        name = self._selected_pool()
        if name and messagebox.askyesno("删除", f"删除池子「{name}」?", parent=self.root):
            self._collect_all()
            del self.cfg["pools"][name]
            self._refresh_pool_list()

    # ----- 原始 JSON -----

    def _raw_export(self):
        self._collect_all()
        self.raw_text.delete("1.0", "end")
        self.raw_text.insert("1.0", json.dumps(self.cfg, ensure_ascii=False, indent=2))
        self.msg("表单已导出到 JSON 框")

    def _raw_apply(self):
        try:
            self.cfg = normalize(json.loads(self.raw_text.get("1.0", "end-1c")))
        except (json.JSONDecodeError, AttributeError, TypeError) as e:
            self.msg(f"JSON 解析失败: {e}", ok=False)
            return
        self._rebuild_all()
        self.msg("JSON 已应用到表单(还没保存)")

    # ----- 读写 -----

    def _headers(self, json_body=False):
        headers = {"Content-Type": "application/json"} if json_body else {}
        if self.token.get().strip():
            headers["Authorization"] = "Bearer " + self.token.get().strip()
        return headers

    def _http(self, method, path, body=None, timeout=3):
        data = body.encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.bridge + path, data=data, method=method,
                                     headers=self._headers(json_body=body is not None))
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")

    def _rebuild_all(self):
        self.gift_collectors = []
        self.pool_collectors = []
        self._refresh_gift_list()
        self._refresh_pool_list()
        self._rebuild_globals()

    def load(self, silent=False):
        try:
            _, text = self._http("GET", "/config")
            self.cfg = normalize(json.loads(text))
            self.online = True
            self._rebuild_all()
            if not silent:
                self.msg("已从服务端读取(在线模式)")
            return
        except Exception:
            self.online = False
        try:
            if self.config_path.exists():
                self.cfg = normalize(json.loads(self.config_path.read_text(encoding="utf-8")))
                if not silent:
                    self.msg(f"服务端未开:已读本地文件 {self.config_path.name}(离线模式)")
            else:
                self.cfg = default_config()
                if not silent:
                    self.msg("服务端未开且配置文件不存在:从默认模板开始(保存会创建文件)", ok=False)
        except Exception as e:
            self.cfg = default_config()
            self.msg(f"读取失败,回落默认模板: {e}", ok=False)
        self._rebuild_all()

    def save(self):
        self._collect_all()
        body = json.dumps(self.cfg, ensure_ascii=False, indent=2)
        try:
            status, text = self._http("POST", "/config", body=body)
            if status == 200:
                info = json.loads(text)
                self.msg(f"已保存并热加载({info.get('gifts', '?')} 个礼物生效),无需重启")
            else:
                self.msg(f"保存被拒 HTTP {status}: {text}", ok=False)
            return
        except urllib.error.HTTPError as e:
            self.msg(f"保存被拒 HTTP {e.code}: {e.read().decode('utf-8', 'replace')}(token 对吗?)", ok=False)
            return
        except Exception:
            pass  # 服务端没开 → 直写文件
        try:
            self.config_path.parent.mkdir(parents=True, exist_ok=True)
            temp = self.config_path.with_suffix(".json.tmp")
            temp.write_text(body, encoding="utf-8")
            os.replace(temp, self.config_path)
            self.msg(f"服务端未开:已直写 {self.config_path}(开服自动生效)")
        except Exception as e:
            self.msg(f"写文件失败: {e}", ok=False)

    def _test_gift(self):
        name = self._selected_gift()
        if not name:
            self.msg("先在左侧选一个礼物", ok=False)
            return
        if not self.online:
            self.msg("试一发需要 MC 服务端在线", ok=False)
            return
        payload = json.dumps({"user": self.test_user.get().strip() or "面板测试", "gift": name, "count": 1},
                             ensure_ascii=False)
        try:
            status, text = self._http("POST", "/gift", body=payload)
            self.msg(f"🧪 {name}: 已投递,看游戏里反应" if status == 200 else f"🧪 {name}: {status} {text}",
                     ok=status == 200)
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            hint = "被去重/冷却拦了,等 2 秒再试" if e.code == 429 else detail
            self.msg(f"🧪 {name}: HTTP {e.code} {hint}", ok=False)
        except Exception as e:
            self.msg(f"🧪 投递失败: {e}", ok=False)

    # ----- 健康轮询 / 状态持久化 -----

    def _poll_health(self):
        def worker():
            try:
                self._http("GET", "/health", timeout=2)
                ok = True
            except Exception:
                ok = False

            def update():
                self.online = ok
                self.conn_label.configure(text="🟢 在线(热加载)" if ok else "🟡 离线(直改文件)")
            self.root.after(0, update)
        threading.Thread(target=worker, daemon=True).start()
        self.root.after(5000, self._poll_health)

    def _load_state(self):
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _on_close(self):
        try:
            STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
            STATE_FILE.write_text(json.dumps({
                "token": self.token.get().strip(),
                "testUser": self.test_user.get().strip(),
                "geometry": self.root.winfo_geometry(),
            }, ensure_ascii=False), encoding="utf-8")
        except Exception:
            pass
        self.root.destroy()


def main():
    ap = argparse.ArgumentParser(description="AIBot 礼物规则面板(原生 GUI)")
    ap.add_argument("--bridge", default=DEFAULT_BRIDGE, help="礼物桥地址(默认 %(default)s)")
    ap.add_argument("--config", default=str(DEFAULT_CONFIG), help="离线模式配置文件路径")
    args = ap.parse_args()
    root = tk.Tk()
    GiftPanel(root, args.bridge, Path(args.config))
    root.mainloop()


if __name__ == "__main__":
    main()
