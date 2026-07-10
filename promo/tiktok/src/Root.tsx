import React from "react";
import { Composition } from "remotion";
import { Video } from "./Video";
import { FPS, WIDTH, HEIGHT, TOTAL_FRAMES } from "./theme";

export const RemotionRoot: React.FC = () => {
  return (
    <Composition
      id="Promo"
      component={Video}
      durationInFrames={TOTAL_FRAMES}
      fps={FPS}
      width={WIDTH}
      height={HEIGHT}
    />
  );
};
