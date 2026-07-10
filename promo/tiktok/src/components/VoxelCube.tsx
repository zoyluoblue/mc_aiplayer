import React from "react";
import { hexA } from "./Backdrop";

// A clean isometric voxel cube (3 faces) — the Minecraft nod, done premium not childish.
// size = edge length in px. `face` is the base accent color.
export const VoxelCube: React.FC<{
  size: number;
  face: string;
  style?: React.CSSProperties;
  glow?: boolean;
}> = ({ size, face, style, glow = false }) => {
  const top = hexA(face, 0.95);
  const left = shade(face, 0.7);
  const right = shade(face, 0.5);
  const s = size;
  const h = s * 0.5; // iso half-height

  return (
    <div style={{ position: "absolute", width: s, height: s * 1.5, ...style }}>
      <svg width={s} height={s * 1.5} viewBox={`0 0 ${s} ${s * 1.5}`} style={{ overflow: "visible" }}>
        {glow ? (
          <defs>
            <filter id={`g${s}${face.replace("#", "")}`} x="-50%" y="-50%" width="200%" height="200%">
              <feGaussianBlur stdDeviation={s * 0.06} result="b" />
              <feMerge>
                <feMergeNode in="b" />
                <feMergeNode in="SourceGraphic" />
              </feMerge>
            </filter>
          </defs>
        ) : null}
        <g filter={glow ? `url(#g${s}${face.replace("#", "")})` : undefined}>
          {/* top face (rhombus) */}
          <polygon points={`${s / 2},0 ${s},${h / 2} ${s / 2},${h} 0,${h / 2}`} fill={top} />
          {/* left face */}
          <polygon points={`0,${h / 2} ${s / 2},${h} ${s / 2},${h + s} 0,${h / 2 + s}`} fill={left} />
          {/* right face */}
          <polygon points={`${s},${h / 2} ${s / 2},${h} ${s / 2},${h + s} ${s},${h / 2 + s}`} fill={right} />
        </g>
      </svg>
    </div>
  );
};

function shade(hex: string, k: number): string {
  const h = hex.replace("#", "");
  const r = Math.round(parseInt(h.substring(0, 2), 16) * k);
  const g = Math.round(parseInt(h.substring(2, 4), 16) * k);
  const b = Math.round(parseInt(h.substring(4, 6), 16) * k);
  return `rgb(${r},${g},${b})`;
}
