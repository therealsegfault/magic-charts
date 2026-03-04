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

public class MagicChartsAWT extends Canvas implements KeyListener {

    enum Difficulty { EASY, NORMAL, HARD }

    // --- Config ---
    static final int WIDTH = 400;
    static final int HEIGHT = 600;
    static final int LANES = 4;
    static final int NOTE_WIDTH = 80;
    static final int NOTE_HEIGHT = 20;
    static final int HIT_LINE_Y = HEIGHT - 100;
    static final int FPS = 60;
    static final long APPROACH_TIME_MS = (long)((60000.0 / 149.0) * 6);
    static final long MAX_HIT_WINDOW_MS = 250;
    static final long PERFECT_WINDOW_MS = 110;
    static final long MISS_WINDOW_MS = 250;

    // Lane accent colours
    static final Color[] LANE_COLORS = {
            new Color(0, 220, 255),
            new Color(255, 80, 200),
            new Color(120, 255, 80),
            new Color(255, 200, 40),
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
    int characterX = WIDTH / 2;
    int characterY = HIT_LINE_Y - 64 - 10;
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
    }

    // ── Sparkle helper ───────────────────────────────────────────
    private void spawnSparkle(int lane, int y, int count) {
        float cx = lane * NOTE_WIDTH + NOTE_WIDTH / 2f;
        Color base = LANE_COLORS[lane];
        for (int i = 0; i < count; i++) {
            Color c = (i % 2 == 0) ? base : Color.WHITE;
            particles.add(new Particle(cx, y, c));
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
                        double scrollSpeed = (double)(HIT_LINE_Y + NOTE_HEIGHT) / APPROACH_TIME_MS;
                        int tailY = HIT_LINE_Y - (int)((holdEnd - now) * scrollSpeed);
                        spawnSparkle(n.lane, tailY, 24);

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

    public void paint(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        for (int i = 0; i < LANES; i++) {
            g2.setColor(Color.DARK_GRAY);
            g2.fillRect(i * NOTE_WIDTH, 0, NOTE_WIDTH, HEIGHT);
        }

        long now = getCurrentTimeMs();
        double scrollSpeed = (double)(HIT_LINE_Y + NOTE_HEIGHT) / APPROACH_TIME_MS;

        // --- Draw notes (holds first so heads render on top) ---
        for (Note n : notes) {
            // Skip fully done notes (but keep active holds visible)
            if (n.hit && !(n.isHold() && n.holdStartMs >= 0 && !n.holdComplete)) continue;
            if (now < n.spawnTimeMs) continue;

            int y = HIT_LINE_Y - (int)((n.hitTimeMs - now) * scrollSpeed);

            if (n.isHold()) {
                long holdEnd = n.hitTimeMs + n.durationMs;
                int tailY = HIT_LINE_Y - (int)((holdEnd - now) * scrollSpeed);
                tailY = Math.max(tailY, 0);

                int tailX = n.lane * NOTE_WIDTH + NOTE_WIDTH / 4;
                int tailW = NOTE_WIDTH / 2;

                // Tail body
                g2.setColor(LANE_COLORS[n.lane].darker());
                g2.fillRoundRect(tailX, tailY, tailW, y - tailY + NOTE_HEIGHT / 2, 8, 8);

                // Tail end cap
                g2.setColor(LANE_COLORS[n.lane]);
                g2.fillOval(tailX - 4, tailY - 4, tailW + 8, 16);
            }

            // Note head
            if (y > -NOTE_HEIGHT - 300 && y < HEIGHT + 300) {
                Color noteColor = LANE_COLORS[n.lane];
                g2.setColor(noteColor);
                g2.fillRoundRect(n.lane * NOTE_WIDTH + 6, y, NOTE_WIDTH - 12, NOTE_HEIGHT, 12, 12);
                // Shine
                g2.setColor(new Color(255, 255, 255, 60));
                g2.fillRoundRect(n.lane * NOTE_WIDTH + 10, y + 2, NOTE_WIDTH - 20, NOTE_HEIGHT / 2, 8, 8);
            }
        }

        // --- Draw particles ---
        for (Particle p : new ArrayList<>(particles)) {
            int alpha = Math.max(0, Math.min(255, (int)(p.life * 255)));
            g2.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), alpha));
            int size = (int)(4 + p.life * 6);
            g2.fillOval((int)p.x - size/2, (int)p.y - size/2, size, size);
        }

        // --- Hit line ---
        g2.setColor(Color.RED);
        g2.fillRect(0, HIT_LINE_Y, WIDTH, 5);

        // --- Judgement text ---
        if (!judgementText.isEmpty()) {
            long elapsed = System.currentTimeMillis() - judgementTimer;
            if (elapsed <= JUDGEMENT_DISPLAY_MS) {
                g2.setFont(bigFont);
                g2.setColor(Color.WHITE);
                int strW = g2.getFontMetrics().stringWidth(judgementText);
                g2.drawString(judgementText, WIDTH / 2 - strW / 2, HIT_LINE_Y - 30);
            }
        }

        // --- Character sprite ---
        if (spriteGo != null && spriteHit != null) {
            BufferedImage currentSprite = hitPose ? spriteHit : spriteGo;
            if (hitPose && System.currentTimeMillis() - hitPoseTimer > HIT_POSE_DURATION_MS)
                hitPose = false;
            g2.drawImage(currentSprite, characterX - 32, characterY, 64, 64, null);
        }

        // --- Hit text ---
        if (!hitText.isEmpty()) {
            long elapsed = System.currentTimeMillis() - hitTextTimer;
            if (elapsed <= HIT_TEXT_DURATION_MS) {
                g2.setFont(hugeFont);
                g2.setColor(Color.WHITE);
                String[] lines = hitText.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    int strW = g2.getFontMetrics().stringWidth(lines[i]);
                    g2.drawString(lines[i], characterX - strW / 2, characterY - 20 - i * 60);
                }
            }
        }

        // --- Count-in ---
        if (countingIn && beatIndex < beatText.length) {
            g2.setFont(hugeFont);
            g2.setColor(new Color(255, 255, 255, 220));
            String countStr = beatText[beatIndex % beatText.length];
            int strW = g2.getFontMetrics().stringWidth(countStr);
            g2.drawString(countStr, WIDTH/2 - strW/2, HEIGHT/2);
        }

        // --- Score HUD ---
        g2.setFont(ttfFont);
        g2.setColor(Color.WHITE);
        g2.drawString("Combo: " + combo, 15, 30);
        g2.drawString("Perfect: " + perfectCount + "  Good: " + goodCount + "  Miss: " + missCount, 15, 60);
        int totalHits = perfectCount + goodCount + missCount;
        if (totalHits > 0) {
            double accuracy = ((perfectCount * 1.0) + (goodCount * 0.7)) / totalHits * 100.0;
            g2.drawString("Accuracy: " + String.format("%.2f", accuracy) + "%", 15, 90);
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
                spawnSparkle(lane, HIT_LINE_Y, 8);
            } else {
                combo++;
                if (combo > maxCombo) maxCombo = combo;
                if (offset <= PERFECT_WINDOW_MS) {
                    perfectCount++;
                    judgementText = "PERFECT";
                    spawnSparkle(lane, HIT_LINE_Y, 16);
                } else {
                    goodCount++;
                    judgementText = "GOOD";
                    spawnSparkle(lane, HIT_LINE_Y, 8);
                }
                judgementTimer = System.currentTimeMillis();
                hitPose = true;
                hitPoseTimer = System.currentTimeMillis();
                hitTextTimer = System.currentTimeMillis();
                hitText = offset <= PERFECT_WINDOW_MS
                        ? "WHACK!\nPERFECT (" + offset + "ms)"
                        : "WHACK!\nGOOD (" + offset + "ms)";
            }

            characterX = candidate.lane * NOTE_WIDTH + NOTE_WIDTH / 2;
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
            }
        });

        javax.swing.Timer timer = new javax.swing.Timer(1000 / FPS, evt -> {
            canvas.updateNotes();
            canvas.repaint();
        });
        timer.start();
    }
}