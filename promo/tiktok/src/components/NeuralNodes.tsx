import React from "react";
import { AbsoluteFill, useCurrentFrame, interpolate } from "remotion";
import { theme } from "../theme";
import { hexA } from "./Backdrop";

// Deterministic animated neural-network overlay (the AI motif): nodes pulse, links
// "draw in" with a traveling glow. Pure SVG, seedable, no randomness at runtime.
type Node = { x: number; y: number; r: number };

function mkNodes(n: number): Node[] {
  const out: Node[] = [];
  for (let i = 0; i < n; i++) {
    const s = i * 97.13;
    out.push({
      x: 90 + (Math.sin(s) * 0.5 + 0.5) * 900,
      y: 120 + ((s * 53.7) % 1680),
      r: 5 + (i % 3) * 3,
    });
  }
  return out;
}

export const NeuralNodes: React.FC<{
  count?: number;
  color?: string;
  opacity?: number;
}> = ({ count = 14, color = theme.cyan, opacity = 0.5 }) => {
  const frame = useCurrentFrame();
  const nodes = mkNodes(count);
  // connect each node to its 2 nearest-by-index neighbors for a clean mesh
  const links: [number, number][] = [];
  for (let i = 0; i < nodes.length; i++) {
    links.push([i, (i + 1) % nodes.length]);
    links.push([i, (i + 3) % nodes.length]);
  }

  return (
    <AbsoluteFill style={{ opacity, mixBlendMode: "screen" }}>
      <svg width={1080} height={1920} viewBox="0 0 1080 1920">
        {links.map(([a, b], i) => {
          const draw = interpolate(frame - i * 2, [0, 30], [0, 1], {
            extrapolateLeft: "clamp",
            extrapolateRight: "clamp",
          });
          const na = nodes[a];
          const nb = nodes[b];
          const x2 = na.x + (nb.x - na.x) * draw;
          const y2 = na.y + (nb.y - na.y) * draw;
          return (
            <line
              key={i}
              x1={na.x}
              y1={na.y}
              x2={x2}
              y2={y2}
              stroke={hexA(color, 0.35)}
              strokeWidth={1.5}
            />
          );
        })}
        {nodes.map((nd, i) => {
          const pulse = interpolate(Math.sin(frame / 14 + i), [-1, 1], [0.5, 1]);
          return (
            <g key={i}>
              <circle cx={nd.x} cy={nd.y} r={nd.r * 2.4 * pulse} fill={hexA(color, 0.12)} />
              <circle cx={nd.x} cy={nd.y} r={nd.r * pulse} fill={color} />
            </g>
          );
        })}
      </svg>
    </AbsoluteFill>
  );
};
