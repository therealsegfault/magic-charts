import java.awt.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.*;
import javax.sound.midi.*;
import java.util.*;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.image.WritableImage;

public class MagicChartsAWT extends Canvas implements KeyListener {

    enum Difficulty { EASY, NORMAL, HARD }

    // --- Config ---
    static final int WIDTH  = 1280;
    static final int HEIGHT = 720;
    static final int LANES  = 4;

    // Side-scroll: lanes are horizontal rows
    static final int LANE_HEIGHT   = 100;
    static final int LANE_TOP      = (HEIGHT - LANES * LANE_HEIGHT) / 2;
    static final int HIT_LINE_X    = 220;
    static final int NOTE_W        = 54;
    static final int NOTE_H        = 54;

    static final int FPS = 60;
    static final long APPROACH_TIME_MS = (long)((60000.0 / 149.0) * 6);
    static final long MAX_HIT_WINDOW_MS = 250;
    static final long PERFECT_WINDOW_MS = 110;
    static final long MISS_WINDOW_MS    = 250;

    static final Color[] LANE_COLORS = {
            new Color(  0, 255, 180),   // neon teal
            new Color(255,  30, 120),   // hot magenta
            new Color( 40, 160, 255),   // electric blue
            new Color(255, 200,   0),   // hard yellow
    };
    static final Color[] LANE_BG = {
            new Color(  0,  14,  10),
            new Color( 14,   0,   8),
            new Color(  0,   6,  18),
            new Color( 14,  10,   0),
    };

    // --- Judgement display ---
    String judgementText = "";
    long judgementTimer = 0;
    static final long JUDGEMENT_DISPLAY_MS = 500;

    // --- Character / hit animation ---
    BufferedImage spriteGo = null;
    BufferedImage spriteHit = null;
    boolean hitPose = false;
    long hitPoseTimer = 0;
    static final long HIT_POSE_DURATION_MS = 150;
    int characterX = HIT_LINE_X / 2;
    int characterY = HEIGHT / 2;
    String hitText = "";
    long hitTextTimer = 0;
    static final long HIT_TEXT_DURATION_MS = 500;

    // --- Count-in ---
    String[] beatText = {"ONE","TWO","ONE","TWO","THREE","FOUR"};
    long lastBeatTime = 0;
    int beatIndex = 0;
    static long msPerBeat = (long)(60000.0 / 149.0);
    boolean countingIn = true;
    static final long COUNT_IN_DURATION_MS = msPerBeat * 6;
    Font ttfFont = null;
    Font bigFont = null;
    Font hugeFont = null;

    // --- Double buffer ---
    BufferedImage offscreen = null;

    // --- Background video ---
    static final String BG_VIDEO_PATH = "assets/video/background.mp4";
    MediaPlayer bgPlayer = null;
    MediaView  bgView   = null;
    JFXPanel   jfxPanel = null;   // bootstraps JavaFX runtime
    volatile BufferedImage bgFrame = null; // latest decoded frame

    // --- Particles ---
    List<Particle> particles = new ArrayList<>();

    // --- Hold tracking ---
    Set<Integer> heldLanes = new HashSet<>();

    // ── Particle class ──────────────────────────────────────────
    static class Particle {
        float x, y;
        float vx, vy;
        float life;
        Color color;

        Particle(float x, float y, Color color) {
            this.x = x;
            this.y = y;
            Random rng = new Random();
            float angle = (float)(rng.nextDouble() * Math.PI * 2);
            float speed = 2f + rng.nextFloat() * 4f;
            this.vx = (float) Math.cos(angle) * speed;
            this.vy = (float) Math.sin(angle) * speed - 2f;
            this.life = 1.0f;
            this.color = color;
        }

        void update() {
            x += vx;
            y += vy;
            vy += 0.18f;
            life -= 0.045f;
        }

        boolean isDead() { return life <= 0; }
    }

    // ── Note class ───────────────────────────────────────────────
    static class Note {
        long hitTimeMs;
        long durationMs;        // 0 = tap; >0 = hold
        int lane;
        boolean hit = false;
        boolean holdComplete = false;
        long holdStartMs = -1;
        long spawnTimeMs;
        long approachTime;

        // Tap note
        public Note(long hitTimeMs, int lane) {
            this(hitTimeMs, 0, lane);
        }

        // Hold note
        public Note(long hitTimeMs, long durationMs, int lane) {
            this.approachTime = APPROACH_TIME_MS;
            this.hitTimeMs    = hitTimeMs;
            this.durationMs   = durationMs;
            this.lane         = lane;
            this.spawnTimeMs  = hitTimeMs - approachTime;
        }

        boolean isHold() { return durationMs > 0; }
    }

    List<Note> notes = new ArrayList<>();
    Sequencer sequencer;
    javax.sound.sampled.Clip audioClip = null;
    long startTime = 0;

    // --- Scoring ---
    int combo = 0;
    int maxCombo = 0;
    int perfectCount = 0;
    int goodCount = 0;
    int missCount = 0;

    Map<Integer, Integer> keyToLane = Map.of(
            KeyEvent.VK_A, 0,
            KeyEvent.VK_S, 1,
            KeyEvent.VK_D, 2,
            KeyEvent.VK_F, 3
    );

    public MagicChartsAWT() {
        this(false, null, 120);
    }

    public MagicChartsAWT(boolean useAutochart, String audioFile, int bpm) {
        addKeyListener(this);
        setFocusable(true);
        requestFocusInWindow();

        // Load TTF font
        try {
            ttfFont = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/Roboto-Bold.ttf")).deriveFont(24f);
            bigFont = ttfFont.deriveFont(40f);
            hugeFont = ttfFont.deriveFont(64f);
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(ttfFont);
        } catch (Exception e) {
            ttfFont = new Font("SansSerif", Font.BOLD, 24);
            bigFont = new Font("SansSerif", Font.BOLD, 40);
            hugeFont = new Font("SansSerif", Font.BOLD, 64);
        }

        // Load sprites
        try {
            spriteGo  = javax.imageio.ImageIO.read(new File("assets/sprites/sprite_go.png"));
            spriteHit = javax.imageio.ImageIO.read(new File("assets/sprites/sprite_hit.png"));
        } catch (Exception e) { e.printStackTrace(); }

        try {
            if (useAutochart && audioFile != null) {
                List<Note> loadedNotes = autoChartFromAudio(audioFile, bpm);
                long rawFirstHit = Long.MAX_VALUE;
                for (Note n : loadedNotes) if (n.hitTimeMs < rawFirstHit) rawFirstHit = n.hitTimeMs;
                long shift = APPROACH_TIME_MS - rawFirstHit;
                for (Note n : loadedNotes) {
                    n.hitTimeMs  += shift;
                    n.spawnTimeMs = n.hitTimeMs - APPROACH_TIME_MS;
                }
                notes = adjustLanesForSpacing(loadedNotes, 150);
                notes.sort(Comparator.comparingLong(n -> n.hitTimeMs));
                javax.sound.sampled.AudioInputStream ais =
                        javax.sound.sampled.AudioSystem.getAudioInputStream(new File(audioFile));
                audioClip = javax.sound.sampled.AudioSystem.getClip();
                audioClip.open(ais);
                long audioDelayMs = rawFirstHit < 0 ? 0 : rawFirstHit;
                startTime = System.currentTimeMillis();
                if (audioDelayMs > 0) {
                    javax.swing.Timer audioStartTimer = new javax.swing.Timer((int) audioDelayMs, evt -> {
                        if (audioClip != null && !audioClip.isRunning()) audioClip.start();
                    });
                    audioStartTimer.setRepeats(false);
                    audioStartTimer.start();
                } else {
                    audioClip.start();
                }
            } else {
                notes = loadMidi("assets/midi/wornouttapes.mid", Difficulty.NORMAL);
                notes.sort(Comparator.comparingLong(n -> n.hitTimeMs));
                sequencer = MidiSystem.getSequencer();
                sequencer.open();
                Sequence sequence = MidiSystem.getSequence(new File("assets/midi/wornouttapes.mid"));
                sequencer.setSequence(sequence);
                sequencer.start();
                startTime = System.currentTimeMillis();
            }
        } catch (Exception e) { e.printStackTrace(); }

        // ── TEST HOLD NOTE — remove once verified ──
        // notes.add(new Note(2000, 1000, 0)); // lane 0, hits at 2s, hold for 1s
        // notes.sort(Comparator.comparingLong(n -> n.hitTimeMs));

        // ── Background video ──
        initBackgroundVideo();
    }

    private void initBackgroundVideo() {
        File videoFile = new File(BG_VIDEO_PATH);
        if (!videoFile.exists()) return; // silently skip if no video present

        // JFXPanel bootstraps the JavaFX runtime without needing Application.launch()
        jfxPanel = new JFXPanel();

        Platform.runLater(() -> {
            try {
                Media media = new Media(videoFile.toURI().toString());
                bgPlayer = new MediaPlayer(media);
                bgPlayer.setCycleCount(MediaPlayer.INDEFINITE); // loop forever
                bgPlayer.setMute(true);                          // video is BG only, audio is separate
                bgPlayer.setVolume(0);

                bgView = new MediaView(bgPlayer);
                bgView.setFitWidth(WIDTH);
                bgView.setFitHeight(HEIGHT);
                bgView.setPreserveRatio(false);

                StackPane root = new StackPane(bgView);
                root.setPrefSize(WIDTH, HEIGHT);
                Scene scene = new Scene(root, WIDTH, HEIGHT);
                jfxPanel.setScene(scene);

                // Capture a frame every ~16ms (≈60fps) into bgFrame
                bgPlayer.currentTimeProperty().addListener((obs, oldT, newT) -> {
                    Platform.runLater(() -> {
                        WritableImage fxImg = jfxPanel.getScene().snapshot(null);
                        if (fxImg != null)
                            bgFrame = SwingFXUtils.fromFXImage(fxImg, bgFrame);
                    });
                });

                bgPlayer.play();
            } catch (Exception e) {
                System.err.println("Background video failed to load: " + e.getMessage());
            }
        });
    }

    // ── Sparkle helper ───────────────────────────────────────────
    private void spawnSparkle(int lane, int x, int count) {
        int laneY = LANE_TOP + lane * LANE_HEIGHT + LANE_HEIGHT / 2;
        Color base = LANE_COLORS[lane];
        for (int i = 0; i < count; i++) {
            Color c = (i % 2 == 0) ? base : Color.WHITE;
            particles.add(new Particle(x, laneY, c));
        }
    }

    public static List<Note> autoChartFromAudio(String file, int BPM) {
        List<Note> notes = new ArrayList<>();
        try {
            javax.sound.sampled.AudioInputStream ais = javax.sound.sampled.AudioSystem.getAudioInputStream(new File(file));
            javax.sound.sampled.AudioFormat format = ais.getFormat();
            boolean isBigEndian = format.isBigEndian();
            int bytesPerSample = format.getSampleSizeInBits() / 8;
            float sampleRate = format.getSampleRate();
            int frameSize = format.getFrameSize();
            byte[] audioBytes = ais.readAllBytes();
            int totalFrames = audioBytes.length / frameSize;
            int windowSize = (int)(sampleRate * 0.02);
            double[] envelope = new double[totalFrames / windowSize + 1];
            for (int w = 0; w < envelope.length; w++) {
                double sum = 0; int count = 0;
                int frameStart = w * windowSize;
                int frameEnd = Math.min((w + 1) * windowSize, totalFrames);
                for (int f = frameStart; f < frameEnd; f++) {
                    int idx = f * frameSize;
                    int sample = 0;
                    if (bytesPerSample == 2) {
                        int low  = audioBytes[idx]     & 0xFF;
                        int high = audioBytes[idx + 1] & 0xFF;
                        sample = isBigEndian ? (high << 8) | low : (low << 8) | high;
                        if (sample > 32767) sample -= 65536;
                    } else if (bytesPerSample == 1) {
                        sample = (audioBytes[idx] & 0xFF) - 128;
                    }
                    sum += sample * (double) sample; count++;
                }
                envelope[w] = count > 0 ? Math.sqrt(sum / count) : 0;
            }
            double maxEnv = 0;
            for (double v : envelope) if (v > maxEnv) maxEnv = v;
            if (maxEnv > 0) for (int i = 0; i < envelope.length; i++) envelope[i] /= maxEnv;
            double threshold = 0.04;
            List<Integer> peaks = new ArrayList<>();
            for (int i = 1; i < envelope.length - 1; i++) {
                if (envelope[i] > threshold) { peaks.add(i); i += 4; }
            }
            double msPerBeat = 60000.0 / BPM;
            Set<Long> snapped = new HashSet<>();
            for (int idx : peaks) {
                double timeMs = idx * windowSize * 1000.0 / sampleRate;
                long beatIdx = Math.round(timeMs / msPerBeat);
                long snappedMs = (long) Math.round(beatIdx * msPerBeat);
                snapped.add(snappedMs);
            }
            List<Long> sorted = new ArrayList<>(snapped);
            sorted.sort(Long::compareTo);
            long minSep = 60;
            List<Long> filtered = new ArrayList<>();
            long last = -minSep - 1;
            for (long t : sorted) {
                if (t - last >= minSep) { filtered.add(t); last = t; }
            }
            Random rng = new Random(42);
            Map<Long, Set<Integer>> usedAtTime = new HashMap<>();
            for (long t : filtered) {
                int lane = rng.nextInt(LANES);
                Set<Integer> used = usedAtTime.computeIfAbsent(t, k -> new HashSet<>());
                if (used.contains(lane)) {
                    for (int l = 0; l < LANES; l++) {
                        if (!used.contains(l)) { lane = l; break; }
                    }
                }
                used.add(lane);
                notes.add(new Note(t, lane));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return notes;
    }

    private static List<Note> adjustLanesForSpacing(List<Note> input, long minSpacingMs) {
        input.sort(Comparator.comparingLong(n -> n.hitTimeMs));
        Map<Long, Set<Integer>> used = new HashMap<>();
        for (Note n : input) {
            Set<Integer> u = used.computeIfAbsent(n.hitTimeMs, k -> new HashSet<>());
            if (u.contains(n.lane)) {
                for (int l = 0; l < LANES; l++) {
                    if (!u.contains(l)) { n.lane = l; break; }
                }
            }
            u.add(n.lane);
        }
        return input;
    }

    public static List<Note> loadMidi(String filename, Difficulty difficulty) {
        List<Note> loadedNotes = new ArrayList<>();
        try {
            Sequence sequence = MidiSystem.getSequence(new File(filename));
            int minPitch = Integer.MAX_VALUE, maxPitch = Integer.MIN_VALUE;
            int resolution = sequence.getResolution();

            // First pass: find pitch range
            for (Track track : sequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    if (!(event.getMessage() instanceof ShortMessage sm)) continue;
                    if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        int pitch = sm.getData1();
                        if (pitch < minPitch) minPitch = pitch;
                        if (pitch > maxPitch) maxPitch = pitch;
                    }
                }
            }
            if (minPitch > maxPitch) return loadedNotes;

            int pitchRange = maxPitch - minPitch + 1;
            int laneSize = Math.max(1, pitchRange / LANES);

            // Second pass: pair NOTE_ON with NOTE_OFF for hold durations
            Map<Integer, Long> noteOnMs = new HashMap<>();
            for (Track track : sequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    if (!(event.getMessage() instanceof ShortMessage sm)) continue;
                    int pitch = sm.getData1();
                    long ms = (long)((event.getTick() * 60000.0) / (resolution * 120));
                    boolean isOn  = sm.getCommand() == ShortMessage.NOTE_ON  && sm.getData2() > 0;
                    boolean isOff = sm.getCommand() == ShortMessage.NOTE_OFF
                            || (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() == 0);
                    if (isOn) {
                        noteOnMs.put(pitch, ms);
                    } else if (isOff && noteOnMs.containsKey(pitch)) {
                        long onMs  = noteOnMs.remove(pitch);
                        long dur   = ms - onMs;
                        int lane   = (pitch - minPitch) / laneSize;
                        if (lane >= LANES) lane = LANES - 1;
                        // Holds need duration > 80ms to avoid accidental holds on fast notes
                        loadedNotes.add(new Note(onMs, dur > 80 ? dur : 0, lane));
                    }
                }
            }

            loadedNotes.sort(Comparator.comparingLong(n -> n.hitTimeMs));

            long minSpacing; int maxChordSize;
            switch (difficulty) {
                case EASY   -> { minSpacing = 250; maxChordSize = 1; }
                case NORMAL -> { minSpacing = 150; maxChordSize = 2; }
                case HARD   -> { minSpacing = 80;  maxChordSize = LANES; }
                default     -> { minSpacing = 150; maxChordSize = 2; }
            }

            List<Note> spacingFiltered = new ArrayList<>();
            long lastTime = -9999;
            for (Note n : loadedNotes) {
                if (n.hitTimeMs - lastTime >= minSpacing) {
                    spacingFiltered.add(n);
                    lastTime = n.hitTimeMs;
                }
            }

            Map<Long, List<Note>> byTime = new HashMap<>();
            for (Note n : spacingFiltered)
                byTime.computeIfAbsent(n.hitTimeMs, k -> new ArrayList<>()).add(n);

            List<Note> finalNotes = new ArrayList<>();
            for (List<Note> group : byTime.values()) {
                group.sort(Comparator.comparingInt(n -> n.lane));
                for (int i = 0; i < Math.min(maxChordSize, group.size()); i++)
                    finalNotes.add(group.get(i));
            }
            loadedNotes = finalNotes;

        } catch (Exception e) { e.printStackTrace(); }
        return loadedNotes;
    }

    public void updateNotes() {
        long now = getCurrentTimeMs();

        // --- Count-in ---
        if (countingIn) {
            long sysNow = System.currentTimeMillis();
            if (sysNow - lastBeatTime >= msPerBeat) {
                lastBeatTime = sysNow;
                hitText = beatText[beatIndex % beatText.length];
                hitTextTimer = sysNow;
                beatIndex++;
            }
            if (sysNow - startTime >= COUNT_IN_DURATION_MS) countingIn = false;
        }

        for (Note n : notes) {
            if (n.hit) {
                // Tick active hold notes
                if (n.isHold() && n.holdStartMs >= 0 && !n.holdComplete) {
                    boolean stillHeld = heldLanes.contains(n.lane);
                    long holdEnd = n.hitTimeMs + n.durationMs;

                    if (now >= holdEnd) {
                        // Full hold complete!
                        n.holdComplete = true;
                        combo++;
                        if (combo > maxCombo) maxCombo = combo;
                        perfectCount++;
                        judgementText = "PERFECT HOLD";
                        judgementTimer = System.currentTimeMillis();
                        double scrollSpeed = (double)(WIDTH - HIT_LINE_X + NOTE_W) / APPROACH_TIME_MS;
                        int tailX = HIT_LINE_X + (int)((holdEnd - now) * scrollSpeed);
                        spawnSparkle(n.lane, tailX, 24);

                    } else if (!stillHeld) {
                        // Released early
                        n.holdComplete = true;
                        long held = now - n.holdStartMs;
                        float frac = (float) held / n.durationMs;
                        if (frac >= 0.75f) {
                            goodCount++;
                            judgementText = "GOOD HOLD";
                        } else {
                            combo = 0;
                            missCount++;
                            judgementText = "MISS (released)";
                        }
                        judgementTimer = System.currentTimeMillis();
                    }
                }
                continue;
            }

            // Auto-miss
            if (now - n.hitTimeMs > MISS_WINDOW_MS) {
                n.hit = true;
                combo = 0;
                missCount++;
                judgementText = "MISS";
                judgementTimer = System.currentTimeMillis();
                hitPose = true;
                hitPoseTimer = System.currentTimeMillis();
                hitTextTimer = System.currentTimeMillis();
                hitText = "WHACK!\nMISS";
            }
        }

        // Update particles
        particles.removeIf(Particle::isDead);
        for (Particle p : particles) p.update();
    }

    private long getCurrentTimeMs() {
        if (sequencer != null && sequencer.isOpen())
            return sequencer.getMicrosecondPosition() / 1000;
        else
            return System.currentTimeMillis() - startTime;
    }

    // Prevent AWT from clearing the canvas before paint — eliminates flicker
    @Override
    public void update(Graphics g) { paint(g); }

    public void paint(Graphics g) {
        // Lazily create offscreen buffer
        if (offscreen == null || offscreen.getWidth() != WIDTH || offscreen.getHeight() != HEIGHT)
            offscreen = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g2 = offscreen.createGraphics();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        renderFrame(g2);
        g2.dispose();

        // Flush the finished frame to screen in one shot
        g.drawImage(offscreen, 0, 0, null);
    }

    private void renderFrame(Graphics2D g2) {

        // ── Background: video frame or fallback solid ──────────────
        if (bgFrame != null) {
            g2.drawImage(bgFrame, 0, 0, WIDTH, HEIGHT, null);
            // Dark overlay so the game elements stay readable
            g2.setColor(new Color(0, 0, 0, 120));
            g2.fillRect(0, 0, WIDTH, HEIGHT);
        } else {
            g2.setColor(new Color(4, 4, 8));
            g2.fillRect(0, 0, WIDTH, HEIGHT);
        }

        // Scanlines over the top of video too
        for (int y = 0; y < HEIGHT; y += 4) {
            g2.setColor(new Color(0, 0, 0, y % 8 == 0 ? 40 : 15));
            g2.drawLine(0, y, WIDTH, y);
        }

        long now = getCurrentTimeMs();
        double scrollSpeed = (double)(WIDTH - HIT_LINE_X + NOTE_W) / APPROACH_TIME_MS;

        // ── Lane rows ──────────────────────────────────────────────
        for (int i = 0; i < LANES; i++) {
            int laneY = LANE_TOP + i * LANE_HEIGHT;
            Color lc  = LANE_COLORS[i];

            // Near-black lane bg
            g2.setColor(LANE_BG[i]);
            g2.fillRect(HIT_LINE_X, laneY, WIDTH - HIT_LINE_X, LANE_HEIGHT);

            // Hard neon top edge — 2px bright line
            g2.setColor(lc);
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.drawLine(HIT_LINE_X, laneY, WIDTH, laneY);

            // Soft glow under that edge
            for (int gx = 1; gx <= 6; gx++) {
                g2.setColor(new Color(lc.getRed(), lc.getGreen(), lc.getBlue(),
                        Math.max(0, 50 - gx * 8)));
                g2.drawLine(HIT_LINE_X, laneY + gx, WIDTH, laneY + gx);
            }
        }
        // Bottom edge of last lane
        Color lastLc = LANE_COLORS[LANES - 1];
        g2.setColor(lastLc);
        g2.setStroke(new java.awt.BasicStroke(2f));
        g2.drawLine(HIT_LINE_X, LANE_TOP + LANES * LANE_HEIGHT,
                WIDTH,      LANE_TOP + LANES * LANE_HEIGHT);
        g2.setStroke(new java.awt.BasicStroke(1f));

        // ── Hit line ───────────────────────────────────────────────
        // Multi-pass glow: wide soft → narrow bright
        int[] glowW   = { 28, 18, 10, 5, 2 };
        int[] glowA   = {  8, 16, 35, 80, 255 };
        for (int gi = 0; gi < glowW.length; gi++) {
            g2.setColor(new Color(255, 255, 255, glowA[gi]));
            g2.setStroke(new java.awt.BasicStroke(glowW[gi],
                    java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER));
            g2.drawLine(HIT_LINE_X, LANE_TOP,
                    HIT_LINE_X, LANE_TOP + LANES * LANE_HEIGHT);
        }
        g2.setStroke(new java.awt.BasicStroke(1f));

        // ── Per-lane receptors: diamond shape ─────────────────────
        for (int i = 0; i < LANES; i++) {
            int laneY  = LANE_TOP + i * LANE_HEIGHT + LANE_HEIGHT / 2;
            boolean held = heldLanes.contains(i);
            Color lc = LANE_COLORS[i];
            int r = NOTE_W / 2 - 2;

            // Diamond points
            int[] dx = { HIT_LINE_X,     HIT_LINE_X + r, HIT_LINE_X,     HIT_LINE_X - r };
            int[] dy = { laneY - r,      laneY,           laneY + r,      laneY           };

            // Glow fill when held
            if (held) {
                g2.setColor(new Color(lc.getRed(), lc.getGreen(), lc.getBlue(), 60));
                int gr = r + 8;
                int[] gdx = { HIT_LINE_X, HIT_LINE_X+gr, HIT_LINE_X, HIT_LINE_X-gr };
                int[] gdy = { laneY-gr,   laneY,          laneY+gr,   laneY          };
                g2.fillPolygon(gdx, gdy, 4);
            }

            // Fill
            g2.setColor(held ? lc : new Color(lc.getRed(), lc.getGreen(), lc.getBlue(), 40));
            g2.fillPolygon(dx, dy, 4);

            // Hard neon outline
            g2.setColor(lc);
            g2.setStroke(new java.awt.BasicStroke(held ? 2.5f : 1.5f));
            g2.drawPolygon(dx, dy, 4);
            g2.setStroke(new java.awt.BasicStroke(1f));
        }

        // ── Notes ─────────────────────────────────────────────────
        for (Note n : notes) {
            if (n.hit && !(n.isHold() && n.holdStartMs >= 0 && !n.holdComplete)) continue;
            if (now < n.spawnTimeMs) continue;

            int laneY = LANE_TOP + n.lane * LANE_HEIGHT + LANE_HEIGHT / 2;
            int nx = HIT_LINE_X + (int)((n.hitTimeMs - now) * scrollSpeed);
            Color lc = LANE_COLORS[n.lane];

            if (n.isHold()) {
                long holdEnd   = n.hitTimeMs + n.durationMs;
                int  tailRight = HIT_LINE_X + (int)((holdEnd - now) * scrollSpeed);
                tailRight = Math.min(tailRight, WIDTH);
                int tailH = 10;
                // Glow bar
                g2.setColor(new Color(lc.getRed(), lc.getGreen(), lc.getBlue(), 40));
                g2.fillRect(nx, laneY - tailH - 4, tailRight - nx, tailH * 2 + 8);
                // Core bar
                g2.setColor(new Color(lc.getRed(), lc.getGreen(), lc.getBlue(), 180));
                g2.fillRect(nx, laneY - tailH / 2, tailRight - nx, tailH);
                // Bright top line
                g2.setColor(lc);
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.drawLine(nx, laneY - tailH / 2, tailRight, laneY - tailH / 2);
                g2.setStroke(new java.awt.BasicStroke(1f));
            }

            if (nx > -NOTE_W - 10 && nx < WIDTH + 10)
                drawMusicNote(g2, nx, laneY, lc);
        }

        // ── Particles ─────────────────────────────────────────────
        for (Particle p : new ArrayList<>(particles)) {
            int alpha = Math.max(0, Math.min(255, (int)(p.life * 255)));
            Color pc = p.color;
            // Glow halo
            g2.setColor(new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), alpha / 5));
            int gsize = (int)(10 + p.life * 12);
            g2.fillOval((int)p.x - gsize/2, (int)p.y - gsize/2, gsize, gsize);
            // Core dot
            g2.setColor(new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), alpha));
            int size = (int)(3 + p.life * 4);
            g2.fillOval((int)p.x - size/2, (int)p.y - size/2, size, size);
        }

        // ── Placeholder character ─────────────────────────────────
        drawCharacter(g2, now);

        // ── Judgement text ────────────────────────────────────────
        if (!judgementText.isEmpty()) {
            long elapsed = System.currentTimeMillis() - judgementTimer;
            if (elapsed <= JUDGEMENT_DISPLAY_MS) {
                float fade = 1f - (float)elapsed / JUDGEMENT_DISPLAY_MS;
                // Slide upward slightly
                int jx = HIT_LINE_X + 40;
                int jy = LANE_TOP - 16 - (int)(fade * 0);

                Color jc = judgementText.contains("PERFECT") ? new Color(  0, 255, 180) :
                        judgementText.contains("GOOD")    ? new Color( 40, 160, 255) :
                                new Color(255,  30, 120);
                int alpha = (int)(fade * 255);

                // Wide glow pass
                g2.setFont(bigFont);
                g2.setColor(new Color(jc.getRed(), jc.getGreen(), jc.getBlue(), alpha / 5));
                for (int ox = -4; ox <= 4; ox += 2)
                    g2.drawString(judgementText, jx + ox, jy);

                // Solid text
                g2.setColor(new Color(jc.getRed(), jc.getGreen(), jc.getBlue(), alpha));
                g2.drawString(judgementText, jx, jy);

                // Bright white core
                g2.setColor(new Color(255, 255, 255, alpha / 3));
                g2.drawString(judgementText, jx, jy);
            }
        }

        // ── Count-in ──────────────────────────────────────────────
        if (countingIn && beatIndex < beatText.length) {
            g2.setFont(hugeFont);
            String countStr = beatText[beatIndex % beatText.length];
            int strW = g2.getFontMetrics().stringWidth(countStr);
            int cx2 = WIDTH / 2 - strW / 2;
            int cy2 = HEIGHT / 2;
            // Glow
            g2.setColor(new Color(0, 255, 180, 40));
            for (int ox = -6; ox <= 6; ox += 3)
                g2.drawString(countStr, cx2 + ox, cy2);
            g2.setColor(new Color(0, 255, 180, 220));
            g2.drawString(countStr, cx2, cy2);
            g2.setColor(new Color(255, 255, 255, 120));
            g2.drawString(countStr, cx2, cy2);
        }

        // ── HUD ───────────────────────────────────────────────────
        drawHUD(g2);

        if (hitPose && System.currentTimeMillis() - hitPoseTimer > HIT_POSE_DURATION_MS)
            hitPose = false;
    } // end renderFrame

    /** Draws a neon music note centred at (cx, cy). */
    private void drawMusicNote(Graphics2D g2, int cx, int cy, Color color) {
        int hw = NOTE_W / 2, hh = (int)(NOTE_H * 0.36);
        java.awt.geom.AffineTransform old = g2.getTransform();
        g2.translate(cx, cy);
        g2.rotate(Math.toRadians(-18));

        // Glow halo — wide, very transparent
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 35));
        g2.fillOval(-hw - 10, -hh - 10, hw * 2 + 20, hh * 2 + 20);

        // Mid glow
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
        g2.fillOval(-hw - 4, -hh - 4, hw * 2 + 8, hh * 2 + 8);

        // Dark fill — note is mostly hollow/dark with bright edge
        g2.setColor(new Color(4, 4, 8));
        g2.fillOval(-hw, -hh, hw * 2, hh * 2);

        // Bright neon outline
        g2.setColor(color);
        g2.setStroke(new java.awt.BasicStroke(2.5f));
        g2.drawOval(-hw, -hh, hw * 2, hh * 2);
        g2.setStroke(new java.awt.BasicStroke(1f));

        // Small bright interior fill (core hotspot)
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 160));
        g2.fillOval(-hw / 2, -hh / 2, hw, hh);

        g2.setTransform(old);

        // Stem
        int stemX   = cx + (int)(NOTE_W * 0.28);
        int stemBot = cy - (int)(NOTE_H * 0.28);
        int stemTop = cy - NOTE_H - 4;

        // Glow stroke
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
        g2.setStroke(new java.awt.BasicStroke(7f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g2.drawLine(stemX, stemBot, stemX, stemTop);
        // Bright core
        g2.setColor(color);
        g2.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g2.drawLine(stemX, stemBot, stemX, stemTop);

        // Flag
        g2.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g2.draw(new java.awt.geom.QuadCurve2D.Float(
                stemX, stemTop,
                stemX + 20, stemTop + 10,
                stemX + 12, stemTop + 24
        ));
        g2.setStroke(new java.awt.BasicStroke(1f));
    }

    /** Placeholder character — neon cyber style. */
    private void drawCharacter(Graphics2D g2, long now) {
        int panelW = HIT_LINE_X;
        int cx = panelW / 2;
        int cy = HEIGHT / 2;

        // Panel bg
        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRect(0, 0, panelW, HEIGHT);

        // Neon separator line
        Color sep = LANE_COLORS[0];
        for (int gx = 4; gx >= 0; gx--) {
            g2.setColor(new Color(sep.getRed(), sep.getGreen(), sep.getBlue(),
                    gx == 0 ? 200 : 15 * gx));
            g2.setStroke(new java.awt.BasicStroke(gx * 2 + 1f));
            g2.drawLine(panelW - 1, 0, panelW - 1, HEIGHT);
        }
        g2.setStroke(new java.awt.BasicStroke(1f));

        // Sprite — swap on hit
        BufferedImage sprite = (hitPose && spriteHit != null) ? spriteHit
                : (spriteGo != null)             ? spriteGo
                : null;

        if (sprite != null) {
            int bob = hitPose ? (int)(Math.sin(System.currentTimeMillis() * 0.04) * 5) : 0;
            int sw = sprite.getWidth();
            int sh = sprite.getHeight();
            g2.drawImage(sprite, cx - sw / 2, cy - sh / 2 + bob, sw, sh, null);
        } else {
            // Fallback if no sprites loaded — simple neon rectangle
            g2.setColor(LANE_COLORS[0]);
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.drawRoundRect(cx - 30, cy - 60, 60, 100, 12, 12);
            g2.setStroke(new java.awt.BasicStroke(1f));
            g2.setFont(ttfFont);
            g2.setColor(LANE_COLORS[0]);
            g2.drawString("WAVE", cx - 20, cy + 70);
        }
    }

    /** Score + combo HUD — neon cyber style, top-right. */
    private void drawHUD(Graphics2D g2) {
        int pad = 16;
        int bw  = 210;
        int bh  = 86;
        int rx  = WIDTH - bw - 10;
        int ry  = 10;

        // Dark panel, no rounded softness — hard rect
        g2.setColor(new Color(2, 4, 8, 210));
        g2.fillRect(rx, ry, bw, bh);

        // Neon border — teal top+left, darker right+bottom (gives depth)
        Color hc = LANE_COLORS[0];
        g2.setColor(hc);
        g2.setStroke(new java.awt.BasicStroke(1.5f));
        g2.drawRect(rx, ry, bw, bh);
        g2.setStroke(new java.awt.BasicStroke(1f));

        // Corner accent marks
        int cm = 10;
        g2.setStroke(new java.awt.BasicStroke(2.5f));
        g2.drawLine(rx, ry,      rx + cm, ry);
        g2.drawLine(rx, ry,      rx,      ry + cm);
        g2.drawLine(rx + bw, ry, rx + bw - cm, ry);
        g2.drawLine(rx + bw, ry, rx + bw, ry + cm);
        g2.setStroke(new java.awt.BasicStroke(1f));

        // Score
        int score = perfectCount * 300 + goodCount * 100;
        String scoreStr = String.format("%08d", score);
        g2.setFont(bigFont);
        // Glow
        g2.setColor(new Color(hc.getRed(), hc.getGreen(), hc.getBlue(), 50));
        for (int ox = -3; ox <= 3; ox += 2)
            g2.drawString(scoreStr, rx + bw - g2.getFontMetrics().stringWidth(scoreStr) - pad + ox, ry + 44);
        // Solid
        g2.setColor(hc);
        g2.drawString(scoreStr, rx + bw - g2.getFontMetrics().stringWidth(scoreStr) - pad, ry + 44);

        // Combo
        if (combo > 0) {
            g2.setFont(ttfFont);
            Color cc = LANE_COLORS[1]; // magenta combo
            String comboStr = combo + "  COMBO";
            int cw = g2.getFontMetrics().stringWidth(comboStr);
            g2.setColor(new Color(cc.getRed(), cc.getGreen(), cc.getBlue(), 40));
            for (int ox = -2; ox <= 2; ox++)
                g2.drawString(comboStr, rx + bw - cw - pad + ox, ry + 70);
            g2.setColor(cc);
            g2.drawString(comboStr, rx + bw - cw - pad, ry + 70);
        }
    }

    public void keyPressed(KeyEvent e) {
        long now = getCurrentTimeMs();
        Integer lane = keyToLane.get(e.getKeyCode());
        if (lane == null) return;

        heldLanes.add(lane);

        Note candidate = null;
        long bestOffset = Long.MAX_VALUE;
        for (Note n : notes) {
            if (n.hit || n.lane != lane) continue;
            long offset = Math.abs(n.hitTimeMs - now);
            if (offset <= MAX_HIT_WINDOW_MS && offset < bestOffset) {
                candidate = n;
                bestOffset = offset;
            }
        }

        if (candidate != null) {
            long offset = Math.abs(candidate.hitTimeMs - now);
            candidate.hit = true;

            if (candidate.isHold()) {
                candidate.holdStartMs = now;
                judgementText = "HOLD!";
                judgementTimer = System.currentTimeMillis();
                spawnSparkle(lane, HIT_LINE_X, 8);
            } else {
                combo++;
                if (combo > maxCombo) maxCombo = combo;
                if (offset <= PERFECT_WINDOW_MS) {
                    perfectCount++;
                    judgementText = "PERFECT";
                    spawnSparkle(lane, HIT_LINE_X, 16);
                } else {
                    goodCount++;
                    judgementText = "GOOD";
                    spawnSparkle(lane, HIT_LINE_X, 8);
                }
                judgementTimer = System.currentTimeMillis();
                hitPose = true;
                hitPoseTimer = System.currentTimeMillis();
                hitTextTimer = System.currentTimeMillis();
                hitText = offset <= PERFECT_WINDOW_MS
                        ? "WHACK!\nPERFECT (" + offset + "ms)"
                        : "WHACK!\nGOOD (" + offset + "ms)";
            }

            characterX = HIT_LINE_X / 2; // centre of left panel
            printScoreStats();

        } else {
            combo = 0;
            missCount++;
            judgementText = "MISS";
            judgementTimer = System.currentTimeMillis();
            hitPose = true;
            hitPoseTimer = System.currentTimeMillis();
            hitTextTimer = System.currentTimeMillis();
            hitText = "WHACK!\nMISS";
            printScoreStats();
        }
    }

    public void keyReleased(KeyEvent e) {
        Integer lane = keyToLane.get(e.getKeyCode());
        if (lane != null) heldLanes.remove(lane);
    }

    public void keyTyped(KeyEvent e) {}

    private void printScoreStats() {
        int totalHits = perfectCount + goodCount + missCount;
        if (totalHits == 0) return;
        double accuracy = ((perfectCount * 1.0) + (goodCount * 0.7)) / totalHits * 100.0;
        System.out.println(
                "Combo: " + combo + " | MaxCombo: " + maxCombo +
                        " | Perfect: " + perfectCount + " | Good: " + goodCount +
                        " | Miss: " + missCount + " | Accuracy: " + String.format("%.2f", accuracy) + "%"
        );
        System.out.println("--------------------------------------------------");
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("MagicCharts AWT");
        boolean useAutochart = false;
        String audioFile = "assets/songs/hasurvoicebeentrulylockedaway.wav";
        int bpm = 149;

        MagicChartsAWT canvas = new MagicChartsAWT(useAutochart, audioFile, bpm);
        canvas.setPreferredSize(new java.awt.Dimension(WIDTH, HEIGHT));
        canvas.setSize(WIDTH, HEIGHT);
        frame.add(canvas);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (canvas.sequencer != null) {
                    canvas.sequencer.stop();
                    canvas.sequencer.close();
                }
                if (canvas.audioClip != null) {
                    canvas.audioClip.stop();
                    canvas.audioClip.close();
                }
                if (canvas.bgPlayer != null)
                    Platform.runLater(() -> canvas.bgPlayer.stop());
            }
        });

        javax.swing.Timer timer = new javax.swing.Timer(1000 / FPS, evt -> {
            canvas.updateNotes();
            canvas.repaint();
        });
        timer.start();
    }
}