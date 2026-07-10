import React from "react";
import { AbsoluteFill, useCurrentFrame, interpolate } from "remotion";
import { theme } from "../theme";

// Atmospheric deep-cave backdrop: radial vignette + slow-breathing accent glow +
// a faint voxel grid + drifting dust motes. Pure CSS/SVG — no external assets.
export const Backdrop: React.FC<{ glow?: string; intensity?: number }> = ({
  glow = theme.green,
  intensity = 1,
}) => {
  const frame = useCurrentFrame();
  const breathe = interpolate(Math.sin(frame / 40), [-1, 1], [0.35, 0.7]) * intensity;

  const motes = Array.from({ length: 26 }, (_, i) => {
    const seed = i * 53.13;
    const x = (Math.sin(seed) * 0.5 + 0.5) * 1080;
    const baseY = ((seed * 7.3) % 1920);
    const y = (baseY - frame * (0.4 + (i % 4) * 0.18)) % 1920;
    const yy = y < 0 ? y + 1920 : y;
    const size = 2 + (i % 3);
    const op = 0.18 + (i % 5) * 0.06;
    return (
      <div
        key={i}
        style={{
          position: "absolute",
          left: x,
          top: yy,
          width: size,
          height: size,
          borderRadius: 1,
          background: i % 3 === 0 ? theme.cyan : theme.green,
          opacity: op,
        }}
      />
    );
  });

  return (
    <AbsoluteFill style={{ background: theme.bg0 }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(120% 80% at 50% 18%, ${theme.bg2} 0%, ${theme.bg1} 42%, ${theme.bg0} 100%)`,
        }}
      />
      {/* faint voxel grid */}
      <AbsoluteFill
        style={{
          backgroundImage: `linear-gradient(${hexA(theme.green, 0.05)} 1px, transparent 1px), linear-gradient(90deg, ${hexA(
            theme.green,
            0.05
          )} 1px, transparent 1px)`,
          backgroundSize: "90px 90px",
          maskImage: "radial-gradient(80% 60% at 50% 40%, black 0%, transparent 80%)",
          WebkitMaskImage: "radial-gradient(80% 60% at 50% 40%, black 0%, transparent 80%)",
        }}
      />
      {/* breathing accent glow */}
      <AbsoluteFill
        style={{
          background: `radial-gradient(46% 30% at 50% 62%, ${hexA(glow, 0.55 * breathe)} 0%, transparent 70%)`,
        }}
      />
      {motes}
      {/* edge vignette */}
      <AbsoluteFill
        style={{
          boxShadow: `inset 0 0 320px 80px ${theme.bg0}`,
        }}
      />
    </AbsoluteFill>
  );
};

// hex + alpha helper
export function hexA(hex: string, a: number): string {
  const h = hex.replace("#", "");
  const r = parseInt(h.substring(0, 2), 16);
  const g = parseInt(h.substring(2, 4), 16);
  const b = parseInt(h.substring(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}
