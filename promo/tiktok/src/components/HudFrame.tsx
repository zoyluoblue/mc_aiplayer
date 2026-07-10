import React from "react";
import { AbsoluteFill, useCurrentFrame, interpolate } from "remotion";
import { theme } from "../theme";
import { hexA } from "./Backdrop";

// A premium tech "HUD" overlay: animated corner brackets + a top status chip +
// faint scanlines. Gives every scene an AI-instrument feel without clutter.
export const HudFrame: React.FC<{ label?: string; color?: string }> = ({
  label = "AIBOT // LIVE",
  color = theme.green,
}) => {
  const frame = useCurrentFrame();
  const reveal = interpolate(frame, [0, 16], [0, 1], { extrapolateRight: "clamp" });
  const bracket = 70;
  const m = 54;
  const corner = (h: "l" | "r", v: "t" | "b"): React.CSSProperties => ({
    position: "absolute",
    width: bracket,
    height: bracket,
    [h === "l" ? "left" : "right"]: m,
    [v === "t" ? "top" : "bottom"]: m,
    borderTop: v === "t" ? `3px solid ${hexA(color, 0.7 * reveal)}` : undefined,
    borderBottom: v === "b" ? `3px solid ${hexA(color, 0.7 * reveal)}` : undefined,
    borderLeft: h === "l" ? `3px solid ${hexA(color, 0.7 * reveal)}` : undefined,
    borderRight: h === "r" ? `3px solid ${hexA(color, 0.7 * reveal)}` : undefined,
  });

  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      {/* scanlines */}
      <AbsoluteFill
        style={{
          backgroundImage: `repeating-linear-gradient(0deg, ${hexA(color, 0.04)} 0px, ${hexA(
            color,
            0.04
          )} 1px, transparent 1px, transparent 5px)`,
          opacity: 0.5,
        }}
      />
      <div style={corner("l", "t")} />
      <div style={corner("r", "t")} />
      <div style={corner("l", "b")} />
      <div style={corner("r", "b")} />
      {/* top status chip */}
      <div
        style={{
          position: "absolute",
          top: m + 6,
          left: 0,
          right: 0,
          textAlign: "center",
          opacity: reveal,
          fontFamily: theme.fontFamily,
          color: hexA(color, 0.9),
          fontSize: 24,
          letterSpacing: 6,
          fontWeight: 600,
        }}
      >
        <span style={{ display: "inline-flex", alignItems: "center", gap: 12 }}>
          <span
            style={{
              width: 12,
              height: 12,
              borderRadius: "50%",
              background: color,
              boxShadow: `0 0 14px ${color}`,
              opacity: interpolate(Math.sin(frame / 8), [-1, 1], [0.3, 1]),
            }}
          />
          {label}
        </span>
      </div>
    </AbsoluteFill>
  );
};
