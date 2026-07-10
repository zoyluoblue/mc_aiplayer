import React from "react";
import { AbsoluteFill, useCurrentFrame, useVideoConfig, spring, interpolate } from "remotion";
import { theme } from "../theme";
import { CAPTION } from "../captions";
import { Backdrop, hexA } from "../components/Backdrop";
import { KenBurnsImage } from "../components/KenBurnsImage";
import { NeuralNodes } from "../components/NeuralNodes";
import { HudFrame } from "../components/HudFrame";
import { Caption } from "../components/Caption";
import { VoxelCube } from "../components/VoxelCube";
import { ChatBubble } from "../components/ChatBubble";

type S = { durationInFrames: number };

// ───────────────────────── 1. HOOK ─────────────────────────
// Cave AI b-roll, slow push-in. A dim, hollow chat box hovers — "it just talks".
export const SceneHook: React.FC<S> = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const flicker = 0.4 + 0.18 * Math.sin(frame / 5) * Math.sin(frame / 11);
  return (
    <AbsoluteFill>
      <KenBurnsImage src="img/cave_ai.png" durationInFrames={durationInFrames} zoom="in" pan="up" focus="50% 42%" grade={theme.cyan} dark={0.46} />
      <div
        style={{
          position: "absolute", top: 470, left: 150, right: 150, height: 150, borderRadius: 22,
          border: `2px solid ${hexA(theme.inkDim, 0.5)}`, background: hexA("#0a1014", 0.55),
          display: "flex", alignItems: "center", paddingLeft: 36, opacity: flicker,
          fontFamily: theme.fontFamily, color: hexA(theme.ink, 0.6), fontSize: 38,
        }}
      >
        <span style={{ color: hexA(theme.inkDim, 0.7) }}>chat&nbsp;▍</span>
      </div>
      <Caption text={CAPTION.hook} durationInFrames={durationInFrames} accent={theme.cyan} />
    </AbsoluteFill>
  );
};

// ───────────────────────── 2. TURN ─────────────────────────
// The hollow chat box shatters into voxels that lock into Bob's blocky body.
export const SceneTurn: React.FC<S> = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const cells = [
    { x: 0, y: 0, c: theme.green }, { x: 0, y: 1, c: theme.green }, { x: -1, y: 1, c: theme.cyan },
    { x: 1, y: 1, c: theme.cyan }, { x: 0, y: 2, c: theme.green }, { x: 0, y: 3, c: theme.gold },
  ];
  const cx = 540, cy = 720, cell = 104;
  return (
    <AbsoluteFill>
      <Backdrop glow={theme.green} />
      {cells.map((b, i) => {
        const a = spring({ frame: frame - 2 - i * 4, fps, config: { damping: 180, mass: 0.6 } });
        const fromX = (b.x - 2) * 120 * (1 - a);
        const fromY = -160 * (1 - a);
        return (
          <VoxelCube key={i} size={cell} face={b.c} glow
            style={{ left: cx - cell / 2 + b.x * cell + fromX, top: cy + b.y * cell + fromY, opacity: a }} />
        );
      })}
      <Caption text={CAPTION.turn} durationInFrames={durationInFrames} accent={theme.green} />
    </AbsoluteFill>
  );
};

// ───────────────────────── 3. TALK ─────────────────────────
// A natural-language command types itself into a chat bubble.
export const SceneTalk: React.FC<S> = ({ durationInFrames }) => (
  <AbsoluteFill>
    <Backdrop glow={theme.cyan} />
    <NeuralNodes count={10} color={theme.cyan} opacity={0.28} />
    <div style={{ position: "absolute", top: 720, left: 0, right: 0, textAlign: "center" }}>
      <ChatBubble text={'"Bob, make me an iron pickaxe."'} startFrame={4} cps={1.5} />
    </div>
    <Caption text={CAPTION.talk} durationInFrames={durationInFrames} accent={theme.cyan} />
  </AbsoluteFill>
);

// ───────────────────────── 4. PLAN ─────────────────────────
// LLM neural mesh on top; an ordered plan of steps reveals beneath it.
export const ScenePlan: React.FC<S> = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const steps = ["understand goal", "back-chain recipes", "sequence tasks", "execute"];
  return (
    <AbsoluteFill>
      <Backdrop glow={theme.green} />
      <NeuralNodes count={16} color={theme.green} opacity={0.6} />
      <div style={{ position: "absolute", top: 760, left: 130, right: 130, display: "flex", flexDirection: "column", gap: 18 }}>
        {steps.map((t, i) => {
          const op = interpolate(frame - 18 - i * 16, [0, 12], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
          return (
            <div key={t} style={{
              opacity: op, transform: `translateX(${(1 - op) * -30}px)`,
              display: "flex", alignItems: "center", gap: 18, padding: "16px 26px",
              borderRadius: 14, background: hexA(theme.bg2, 0.66), border: `2px solid ${hexA(theme.green, 0.4)}`,
              fontFamily: theme.fontFamily, color: theme.ink, fontSize: 36, fontWeight: 600,
            }}>
              <span style={{ color: theme.green, fontWeight: 700 }}>{String(i + 1).padStart(2, "0")}</span>{t}
            </div>
          );
        })}
      </div>
      <Caption text={CAPTION.plan} durationInFrames={durationInFrames} accent={theme.green} />
    </AbsoluteFill>
  );
};

// ───────────────────────── 5. CHAIN ─────────────────────────
// Tool-tree dependency chain lights up in order, each node feeding the next.
export const SceneChain: React.FC<S> = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const chain = [
    { g: "🪵", t: "wood", c: theme.gold }, { g: "🪨", t: "stone", c: theme.inkDim },
    { g: "⛏", t: "ore", c: theme.cyan }, { g: "🔥", t: "furnace", c: theme.lava },
    { g: "⚙", t: "iron pickaxe", c: theme.green },
  ];
  return (
    <AbsoluteFill>
      <Backdrop glow={theme.gold} intensity={0.8} />
      <div style={{ position: "absolute", top: 560, left: 110, right: 110, display: "flex", flexDirection: "column", gap: 14 }}>
        {chain.map((n, i) => {
          const a = interpolate(frame - 8 - i * 18, [0, 12], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
          return (
            <React.Fragment key={n.t}>
              {i > 0 && <div style={{ height: 22, width: 3, marginLeft: 56, background: hexA(theme.green, 0.5 * a) }} />}
              <div style={{
                opacity: 0.3 + 0.7 * a, transform: `scale(${0.96 + 0.04 * a})`,
                display: "flex", alignItems: "center", gap: 22, padding: "18px 28px", borderRadius: 16,
                background: `linear-gradient(120deg, ${hexA(n.c, 0.14 * a)}, ${hexA(theme.bg1, 0.7)})`,
                border: `2px solid ${hexA(n.c, 0.3 + 0.4 * a)}`,
              }}>
                <span style={{ fontSize: 56 }}>{n.g}</span>
                <span style={{ fontFamily: theme.fontFamily, color: theme.ink, fontSize: 40, fontWeight: 700 }}>{n.t}</span>
              </div>
            </React.Fragment>
          );
        })}
      </div>
      <Caption text={CAPTION.chain} durationInFrames={durationInFrames} accent={theme.gold} />
    </AbsoluteFill>
  );
};

// ───────────────────────── 6. DEEP ─────────────────────────
// Diamond-village b-roll, vertical descent; a depth HUD ticks toward Y -59.
export const SceneDeep: React.FC<S> = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const y = Math.round(interpolate(frame, [0, durationInFrames - 10], [20, -59], { extrapolateRight: "clamp" }));
  return (
    <AbsoluteFill>
      <KenBurnsImage src="img/diamond_village.png" durationInFrames={durationInFrames} zoom="in" pan="down" focus="62% 50%" grade={theme.cyan} dark={0.4} />
      <HudFrame label="DESCENDING" color={theme.cyan} />
      <div style={{ position: "absolute", top: 380, left: 0, right: 0, textAlign: "center" }}>
        <div style={{ fontFamily: theme.fontFamily, color: theme.cyan, fontSize: 150, fontWeight: 700, letterSpacing: -2, textShadow: `0 6px 50px ${hexA(theme.cyan, 0.5)}` }}>
          Y {y}
        </div>
      </div>
      <Caption text={CAPTION.deep} durationInFrames={durationInFrames} accent={theme.cyan} />
    </AbsoluteFill>
  );
};

// ───────────────────────── 7. SURVIVE ─────────────────────────
// Cave b-roll (different crop) + survival event badges flag in; tense HUD.
export const SceneSurvive: React.FC<S> = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const badges = [
    { t: "lava sealed", c: theme.lava, at: 8 },
    { t: "mobs walled off", c: theme.cyan, at: 30 },
    { t: "gear recovered", c: theme.gold, at: 52 },
  ];
  return (
    <AbsoluteFill>
      <KenBurnsImage src="img/cave_ai.png" durationInFrames={durationInFrames} zoom="out" pan="right" focus="40% 55%" grade={theme.lava} dark={0.5} />
      <HudFrame label="SURVIVAL // AUTO" color={theme.gold} />
      <div style={{ position: "absolute", bottom: 470, left: 80, right: 80, display: "flex", flexDirection: "column", gap: 18 }}>
        {badges.map((b) => {
          const op = interpolate(frame - b.at, [0, 12], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
          return (
            <div key={b.t} style={{
              transform: `translateX(${(1 - op) * -36}px)`, opacity: op,
              display: "flex", alignItems: "center", gap: 18, padding: "18px 28px", borderRadius: 16,
              background: hexA(theme.bg2, 0.72), border: `2px solid ${hexA(b.c, 0.5)}`,
            }}>
              <span style={{ width: 16, height: 16, borderRadius: 4, background: b.c, boxShadow: `0 0 16px ${b.c}` }} />
              <span style={{ fontFamily: theme.fontFamily, color: theme.ink, fontSize: 40, fontWeight: 600 }}>✓ {b.t}</span>
            </div>
          );
        })}
      </div>
      <Caption text={CAPTION.survive} durationInFrames={durationInFrames} accent={theme.gold} bottom={250} />
    </AbsoluteFill>
  );
};

// ───────────────────────── 8. LEARN ─────────────────────────
// The neural network brightens and grows; a memory bar fills — "gets sharper".
export const SceneLearn: React.FC<S> = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const fill = interpolate(frame, [10, durationInFrames - 14], [12, 100], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill>
      <Backdrop glow={theme.cyan} />
      <NeuralNodes count={20} color={theme.cyan} opacity={0.7} />
      <div style={{ position: "absolute", top: 470, left: 0, right: 0, textAlign: "center", fontFamily: theme.fontFamily, color: theme.inkDim, fontSize: 30, letterSpacing: 5 }}>
        MEMORY ACROSS SESSIONS
      </div>
      <div style={{ position: "absolute", top: 540, left: 140, right: 140, height: 20, borderRadius: 10, background: hexA(theme.ink, 0.1), overflow: "hidden" }}>
        <div style={{ height: "100%", width: `${fill}%`, background: `linear-gradient(90deg, ${theme.green}, ${theme.cyan})`, boxShadow: `0 0 26px ${hexA(theme.cyan, 0.6)}` }} />
      </div>
      <Caption text={CAPTION.learn} durationInFrames={durationInFrames} accent={theme.cyan} />
    </AbsoluteFill>
  );
};

// ───────────────────────── 9. OUTRO ─────────────────────────
// Artistic "Made by zuuzii" wordmark resolving from drifting voxels.
export const SceneOutro: React.FC<S> = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const a = spring({ frame: frame - 4, fps, config: { damping: 200, mass: 0.8 } });
  const fade = interpolate(frame, [durationInFrames - 12, durationInFrames], [1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const cubes = [-3, -1.5, 1.5, 3];
  return (
    <AbsoluteFill style={{ opacity: fade }}>
      <Backdrop glow={theme.green} intensity={0.7} />
      {cubes.map((dx, i) => {
        const ca = spring({ frame: frame - i * 3, fps, config: { damping: 120 } });
        return <VoxelCube key={i} size={40} face={i % 2 ? theme.cyan : theme.green} glow
          style={{ left: 540 + dx * 90 - 20, top: 690 - (1 - ca) * 120, opacity: ca * 0.7 }} />;
      })}
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}>
        <div style={{ opacity: a, transform: `scale(${0.92 + 0.08 * a})`, fontFamily: theme.fontFamily, color: theme.inkDim, fontSize: 34, letterSpacing: 8, marginBottom: 18 }}>
          MADE BY
        </div>
        <div style={{
          opacity: a, transform: `translateY(${(1 - a) * 24}px)`,
          fontFamily: theme.fontFamily, color: theme.ink, fontSize: 168, fontWeight: 700, letterSpacing: -2,
          textShadow: `0 10px 70px ${hexA(theme.green, 0.5)}`,
        }}>
          zuuzii
        </div>
        <div style={{ marginTop: 26, width: interpolate(a, [0, 1], [0, 260]), height: 4, borderRadius: 2, background: `linear-gradient(90deg, ${theme.green}, ${theme.cyan})`, boxShadow: `0 0 20px ${hexA(theme.cyan, 0.7)}` }} />
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

export const SCENE_COMPONENTS: Record<string, React.FC<S>> = {
  hook: SceneHook, turn: SceneTurn, talk: SceneTalk, plan: ScenePlan, chain: SceneChain,
  deep: SceneDeep, survive: SceneSurvive, learn: SceneLearn, outro: SceneOutro,
};
