import React from "react";
import { AbsoluteFill, Img, staticFile, useCurrentFrame, interpolate } from "remotion";
import { theme } from "../theme";
import { hexA } from "./Backdrop";

// A project b-roll image (text-free) with a slow cinematic Ken-Burns move + a graded
// dark overlay + accent vignette, so no image ever sits static. 9:16 cover-cropped.
export const KenBurnsImage: React.FC<{
  src: string; // relative to public/, e.g. "img/cave_ai.png"
  durationInFrames: number;
  zoom?: "in" | "out";
  pan?: "up" | "down" | "left" | "right";
  focus?: string; // object-position, e.g. "50% 40%"
  grade?: string; // accent color for the wash
  dark?: number; // 0..1 darkening
}> = ({ src, durationInFrames, zoom = "in", pan = "up", focus = "50% 45%", grade = theme.cyan, dark = 0.42 }) => {
  const frame = useCurrentFrame();
  const t = interpolate(frame, [0, durationInFrames], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const scale = zoom === "in" ? 1.06 + 0.12 * t : 1.18 - 0.12 * t;
  const panPx = 46;
  const tx = pan === "left" ? -panPx * t : pan === "right" ? panPx * t : 0;
  const ty = pan === "up" ? -panPx * t : pan === "down" ? panPx * t : 0;

  return (
    <AbsoluteFill style={{ background: theme.bg0, overflow: "hidden" }}>
      <Img
        src={staticFile(src)}
        style={{
          position: "absolute",
          width: "100%",
          height: "100%",
          objectFit: "cover",
          objectPosition: focus,
          transform: `scale(${scale}) translate(${tx}px, ${ty}px)`,
          willChange: "transform",
        }}
      />
      {/* legibility + grade wash */}
      <AbsoluteFill style={{ background: `rgba(5,8,7,${dark})` }} />
      <AbsoluteFill
        style={{
          background: `radial-gradient(120% 70% at 50% 30%, ${hexA(grade, 0.16)} 0%, transparent 60%)`,
          mixBlendMode: "screen",
        }}
      />
      {/* bottom scrim so captions read */}
      <AbsoluteFill
        style={{
          background: `linear-gradient(180deg, transparent 38%, rgba(5,8,7,0.0) 50%, rgba(5,8,7,0.82) 100%)`,
        }}
      />
      {/* edge vignette */}
      <AbsoluteFill style={{ boxShadow: `inset 0 0 300px 70px ${theme.bg0}` }} />
    </AbsoluteFill>
  );
};
