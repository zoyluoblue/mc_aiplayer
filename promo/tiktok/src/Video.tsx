import React from "react";
import { AbsoluteFill, Sequence, Audio, staticFile } from "remotion";
import { loadFont } from "@remotion/google-fonts/SpaceGrotesk";
import { theme, SCENE_LIST, sceneStart } from "./theme";
import { SCENE_COMPONENTS } from "./scenes/Scenes";

const { fontFamily } = loadFont();

// Orchestrator: 9 scenes back-to-back (hard cuts for TikTok energy), each playing its
// matching natural male voiceover clip at its own start so narration runs continuously.
// On-screen text is English only; the source ("Made by zuuzii") appears only in the final scene.
export const Video: React.FC = () => {
  return (
    <AbsoluteFill style={{ background: theme.bg0, fontFamily: fontFamily || theme.fontFamily }}>
      {SCENE_LIST.map((s, i) => {
        const Comp = SCENE_COMPONENTS[s.id];
        return (
          <Sequence key={s.id} from={sceneStart(i)} durationInFrames={s.durationInFrames}>
            <Comp durationInFrames={s.durationInFrames} />
            <Audio src={staticFile(s.voice)} volume={1} />
          </Sequence>
        );
      })}

      {/* Optional music bed (owner supplies): drop public/music.mp3 and uncomment.
          <Audio src={staticFile("music.mp3")} volume={0.16} /> */}
    </AbsoluteFill>
  );
};
