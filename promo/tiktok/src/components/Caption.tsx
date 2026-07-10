import React from "react";
import { useCurrentFrame, useVideoConfig, spring, interpolate } from "remotion";
import { theme } from "../theme";
import { hexA } from "./Backdrop";

// Self-contained caption: springs up + fades, auto-exits near the scene's end.
// Rendered inside each scene with that scene's line and duration (local frame).
export const Caption: React.FC<{
  text: string;
  durationInFrames: number;
  accent?: string;
  bottom?: number;
  size?: number;
}> = ({ text, durationInFrames, accent = theme.green, bottom = 280, size = 70 }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame, fps, config: { damping: 200, mass: 0.7 } });
  const y = interpolate(enter, [0, 1], [44, 0]);
  const exit = interpolate(frame, [durationInFrames - 12, durationInFrames], [1, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const opacity = Math.min(enter, exit);

  return (
    <div
      style={{
        position: "absolute",
        left: 0,
        right: 0,
        bottom,
        padding: "0 90px",
        textAlign: "center",
        transform: `translateY(${y}px)`,
        opacity,
      }}
    >
      <div
        style={{
          fontFamily: theme.fontFamily,
          color: theme.ink,
          fontSize: size,
          fontWeight: 700,
          lineHeight: 1.08,
          letterSpacing: -0.8,
          textShadow: `0 4px 32px rgba(0,0,0,0.75), 0 2px 18px ${hexA(accent, 0.35)}`,
        }}
      >
        {text}
      </div>
    </div>
  );
};
