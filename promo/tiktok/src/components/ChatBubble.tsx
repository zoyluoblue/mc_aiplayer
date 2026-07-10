import React from "react";
import { useCurrentFrame, interpolate } from "remotion";
import { theme } from "../theme";
import { hexA } from "./Backdrop";

// A chat bubble that types its text out, character by character — the "you just talk" beat.
export const ChatBubble: React.FC<{
  text: string;
  startFrame?: number;
  cps?: number; // characters per second-ish (chars per frame * fps)
  style?: React.CSSProperties;
}> = ({ text, startFrame = 0, cps = 1.4, style }) => {
  const frame = useCurrentFrame();
  const local = Math.max(0, frame - startFrame);
  const shown = Math.min(text.length, Math.floor(local * cps));
  const caret = Math.floor(frame / 8) % 2 === 0 ? "▍" : " ";
  const pop = interpolate(local, [0, 10], [0.9, 1], { extrapolateRight: "clamp" });
  const opacity = interpolate(local, [0, 8], [0, 1], { extrapolateRight: "clamp" });

  return (
    <div
      style={{
        display: "inline-block",
        maxWidth: 760,
        padding: "30px 40px",
        borderRadius: 28,
        background: hexA(theme.green, 0.12),
        border: `2px solid ${hexA(theme.green, 0.45)}`,
        boxShadow: `0 20px 80px ${hexA(theme.green, 0.18)}`,
        transform: `scale(${pop})`,
        opacity,
        ...style,
      }}
    >
      <div
        style={{
          fontFamily: theme.fontFamily,
          color: theme.ink,
          fontSize: 44,
          fontWeight: 600,
          lineHeight: 1.25,
        }}
      >
        {text.substring(0, shown)}
        <span style={{ color: theme.green }}>{shown < text.length ? caret : ""}</span>
      </div>
    </div>
  );
};
