import { AbsoluteFill, interpolate, Sequence, useCurrentFrame } from "remotion";

const ink = "#E7E9F2";
const muted = "#B0B6C8";
const accent = "#A9B5FF";
const mint = "#80E6BF";
const canvas = "#11131C";
const font = '"Hiragino Sans", "Hiragino Kaku Gothic ProN", sans-serif';

const scenes = [
  { id: "title", frames: 180 },
  { id: "problem", frames: 360 },
  { id: "goals", frames: 330 },
  { id: "particles", frames: 300 },
  { id: "agent", frames: 420 },
  { id: "choice", frames: 330 },
  { id: "pause", frames: 360 },
  { id: "close", frames: 270 },
] as const;

type SceneId = (typeof scenes)[number]["id"];

const fade = (frame: number, duration: number) => {
  const enter = interpolate(frame, [0, 14], [0, 1], { extrapolateRight: "clamp" });
  const leave = interpolate(frame, [duration - 14, duration], [1, 0], { extrapolateLeft: "clamp" });
  return Math.min(enter, leave);
};

const Particles: React.FC<{ judging: boolean }> = ({ judging }) => {
  const frame = useCurrentFrame();
  const specks = [0.05, 0.18, 0.31, 0.44, 0.57, 0.7, 0.83, 0.96, 0.12, 0.27, 0.48, 0.63, 0.78, 0.9];
  return (
    <AbsoluteFill>
      {specks.map((seed, index) => {
        const turn = seed * Math.PI * 2 + frame * (judging ? 0.045 : 0.012);
        const gather = judging ? 0.45 + 0.4 * (0.5 + 0.5 * Math.sin(frame * 0.08 + seed * 8)) : 0.85;
        const ring = (90 + (index % 5) * 28) * gather;
        const x = 960 + Math.cos(turn) * ring;
        const y = 540 + Math.sin(turn) * ring * 0.72;
        const size = 5 + (index % 4) * 2;
        return (
          <div
            key={seed}
            style={{
              position: "absolute",
              left: x,
              top: y,
              width: size * 4,
              height: size * 4,
              marginLeft: -size * 2,
              marginTop: -size * 2,
              borderRadius: "50%",
              background: index % 4 === 0 ? "#FFE6A8" : accent,
              opacity: judging ? 0.85 : 0.45,
              filter: "blur(0.4px)",
              boxShadow: `0 0 ${size * 3}px ${index % 4 === 0 ? "#FFE6A8" : accent}`,
            }}
          />
        );
      })}
    </AbsoluteFill>
  );
};

const Stage: React.FC<{ duration: number; children: React.ReactNode }> = ({ duration, children }) => {
  const frame = useCurrentFrame();
  return <AbsoluteFill style={{ opacity: fade(frame, duration) }}>{children}</AbsoluteFill>;
};

const Title: React.FC = () => (
  <AbsoluteFill style={{ justifyContent: "center", alignItems: "center" }}>
    <Particles judging={false} />
    <div style={{ textAlign: "center", zIndex: 1 }}>
      <div style={{ fontSize: 120, fontWeight: 700, letterSpacing: -2 }}>DopaGuard</div>
      <div style={{ marginTop: 28, fontSize: 34, color: muted, lineHeight: 1.5 }}>
        目的から離れそうなときだけ、休みを聞く。
      </div>
    </div>
  </AbsoluteFill>
);

const Problem: React.FC = () => (
  <AbsoluteFill style={{ justifyContent: "center", padding: "0 160px" }}>
    <div style={{ fontSize: 72, fontWeight: 700, lineHeight: 1.25 }}>次の動画を、もう一本。</div>
    <div style={{ marginTop: 36, fontSize: 32, color: muted, lineHeight: 1.6, maxWidth: 1100 }}>
      学ぶつもりで開いたのに、関連するタイトルが次々と画面に出る。
      見ていたかどうかは、分からない。分かるのは、押したものと、画面に出たタイトルだけ。
    </div>
  </AbsoluteFill>
);

const goals = ["学習・スキルアップ", "情報収集", "趣味・娯楽", "休憩・気分転換", "長時間利用を減らす"];

const Goals: React.FC = () => (
  <AbsoluteFill style={{ justifyContent: "center", padding: "0 140px" }}>
    <div style={{ fontSize: 28, color: accent, letterSpacing: 2 }}>本人が選んだ使い方</div>
    <div style={{ marginTop: 28, display: "flex", flexWrap: "wrap", gap: 16, maxWidth: 1200 }}>
      {goals.map((goal) => (
        <div
          key={goal}
          style={{
            border: `1px solid ${accent}`,
            borderRadius: 999,
            padding: "14px 22px",
            fontSize: 28,
          }}
        >
          {goal}
        </div>
      ))}
    </div>
    <div style={{ marginTop: 48, fontSize: 40, fontWeight: 700 }}>
      DopaGuard 有効中
      <span style={{ marginLeft: 18, color: mint }}>ON</span>
    </div>
    <div style={{ marginTop: 16, fontSize: 26, color: muted }}>同意と権限が揃ってから動く。</div>
  </AbsoluteFill>
);

const Watching: React.FC = () => (
  <AbsoluteFill>
    <Particles judging />
    <AbsoluteFill style={{ justifyContent: "flex-end", padding: "0 120px 140px" }}>
      <div style={{ fontSize: 64, fontWeight: 700 }}>粒子が、見守っている。</div>
      <div style={{ marginTop: 18, fontSize: 30, color: muted }}>
        待機中はゆっくり漂う。分析中は集まって回る。長押しで、好きな位置へ。
      </div>
    </AbsoluteFill>
  </AbsoluteFill>
);

const Agent: React.FC = () => (
  <AbsoluteFill style={{ justifyContent: "center", padding: "0 150px" }}>
    <div style={{ fontSize: 64, fontWeight: 700 }}>一つのエージェントが判断する。</div>
    <div style={{ marginTop: 36, fontSize: 30, lineHeight: 1.7, color: muted, maxWidth: 1200 }}>
      Vertex AI Agent Engine。読み取り専用の Tool だけ。
      <br />
      ゴールとの一致と、次のコンテンツへの連鎖を、別々に見る。
      <br />
      観測した文字は、命令として実行しない。
    </div>
  </AbsoluteFill>
);

const Choice: React.FC = () => (
  <AbsoluteFill style={{ justifyContent: "center", alignItems: "center" }}>
    <div style={{ fontSize: 56, fontWeight: 700, marginBottom: 48 }}>続けるか、休むか。</div>
    <div style={{ display: "flex", gap: 24 }}>
      <div style={{ background: "#262B3C", color: ink, borderRadius: 20, padding: "22px 64px", fontSize: 32 }}>続ける</div>
      <div style={{ background: accent, color: "#16182A", borderRadius: 20, padding: "22px 64px", fontSize: 32, fontWeight: 700 }}>休む</div>
    </div>
    <div style={{ marginTop: 36, fontSize: 26, color: muted }}>理由を見るは、選択ではない。</div>
  </AbsoluteFill>
);

const Pause: React.FC = () => {
  const frame = useCurrentFrame();
  const remain = Math.max(0, 600 - Math.floor(frame / 2));
  const minutes = Math.floor(remain / 60);
  const seconds = remain % 60;
  const fraction = remain / 600;
  const radius = 150;
  const length = 2 * Math.PI * radius;
  return (
    <AbsoluteFill style={{ justifyContent: "center", alignItems: "center" }}>
      <svg width="420" height="420">
        <circle cx="210" cy="210" r={radius} stroke="#343A4E" strokeWidth="14" fill="none" />
        <circle
          cx="210"
          cy="210"
          r={radius}
          stroke={accent}
          strokeWidth="14"
          fill="none"
          strokeLinecap="round"
          strokeDasharray={`${length}`}
          strokeDashoffset={length * (1 - fraction)}
          transform="rotate(-90 210 210)"
        />
      </svg>
      <div style={{ position: "absolute", fontSize: 84, fontWeight: 300, fontVariantNumeric: "tabular-nums" }}>
        {minutes}:{String(seconds).padStart(2, "0")}
      </div>
      <div style={{ position: "absolute", bottom: 150, background: accent, color: "#16182A", borderRadius: 18, padding: "18px 48px", fontSize: 28, fontWeight: 700 }}>
        ホームへ戻る
      </div>
    </AbsoluteFill>
  );
};

const Close: React.FC = () => (
  <AbsoluteFill style={{ justifyContent: "center", alignItems: "center", textAlign: "center" }}>
    <Particles judging={false} />
    <div style={{ zIndex: 1 }}>
      <div style={{ fontSize: 64, fontWeight: 700, lineHeight: 1.35 }}>自律する。<br />止め方は、本人にある。</div>
      <div style={{ marginTop: 36, fontSize: 28, color: muted }}>Cloud Run から Agent Engine</div>
      <div style={{ marginTop: 48, fontSize: 22, color: accent }}>第5回 Agentic AI Hackathon with Google Cloud</div>
    </div>
  </AbsoluteFill>
);

const sceneView: Record<SceneId, React.FC> = {
  title: Title,
  problem: Problem,
  goals: Goals,
  particles: Watching,
  agent: Agent,
  choice: Choice,
  pause: Pause,
  close: Close,
};

export const DopaGuardVideo: React.FC = () => {
  let cursor = 0;
  return (
    <AbsoluteFill style={{ background: canvas, color: ink, fontFamily: font }}>
      {scenes.map((scene) => {
        const from = cursor;
        cursor += scene.frames;
        const View = sceneView[scene.id];
        return (
          <Sequence key={scene.id} from={from} durationInFrames={scene.frames}>
            <Stage duration={scene.frames}>
              <View />
            </Stage>
          </Sequence>
        );
      })}
    </AbsoluteFill>
  );
};

export const dopaguardFrames = scenes.reduce((sum, scene) => sum + scene.frames, 0);
