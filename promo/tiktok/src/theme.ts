// Brand palette aligned with the existing AIBot posters (deep green-black + green/cyan/gold).
export const theme = {
  bg0: "#050807",
  bg1: "#06100c",
  bg2: "#0a1a14",
  ink: "#f6fff9",
  inkDim: "rgba(246,255,249,0.66)",
  green: "#64f4a5",
  cyan: "#72d7ff",
  gold: "#ffd27a",
  lava: "#ff7a3c",
  diamond: "#7ff0e0",
  fontFamily:
    "'Space Grotesk', 'Inter', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
};

export const FPS = 30;
export const WIDTH = 1080;
export const HEIGHT = 1920;

// Scene timeline — durations (frames @30fps) are derived from the measured voiceover
// clip lengths (public/vo/lineN.wav) so narration runs nearly continuously across ~32s.
// Each scene plays its matching voice clip at its own frame 0.
export type SceneSpec = {
  id: string;
  voice: string; // file under public/
  durationInFrames: number;
};

export const SCENE_LIST: SceneSpec[] = [
  { id: "hook", voice: "vo/line1.wav", durationInFrames: 126 }, // 4.20s
  { id: "turn", voice: "vo/line2.wav", durationInFrames: 78 }, // 2.60s
  { id: "talk", voice: "vo/line3.wav", durationInFrames: 83 }, // 2.75s
  { id: "plan", voice: "vo/line4.wav", durationInFrames: 131 }, // 4.35s
  { id: "chain", voice: "vo/line5.wav", durationInFrames: 123 }, // 4.10s
  { id: "deep", voice: "vo/line6.wav", durationInFrames: 122 }, // 4.08s
  { id: "survive", voice: "vo/line7.wav", durationInFrames: 131 }, // 4.38s
  { id: "learn", voice: "vo/line8.wav", durationInFrames: 112 }, // 3.73s
  { id: "outro", voice: "vo/line9.wav", durationInFrames: 75 }, // 1.90s voice + hold = 2.5s
];

export const TOTAL_FRAMES = SCENE_LIST.reduce((a, s) => a + s.durationInFrames, 0); // 981 = 32.7s

// cumulative start frame of each scene
export function sceneStart(index: number): number {
  let f = 0;
  for (let i = 0; i < index; i++) f += SCENE_LIST[i].durationInFrames;
  return f;
}
