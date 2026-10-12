import { Composition } from "remotion";
import { DopaGuardVideo, dopaguardFrames } from "./DopaGuard";

export const MyComposition = () => {
  return (
    <Composition
      id="DopaGuard"
      component={DopaGuardVideo}
      durationInFrames={dopaguardFrames}
      fps={30}
      width={1920}
      height={1080}
    />
  );
};
