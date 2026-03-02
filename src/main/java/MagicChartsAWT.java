import java.awt.Canvas;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import javax.swing.*;
import javax.sound.midi.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class MagicChartsAWT extends Canvas implements KeyListener {
    enum Difficulty {
        EASY,
        NORMAL,
        HARD
    }
    // --- Config ---
    static final int WIDTH = 400;
    static final int HEIGHT = 600;
    static final int LANES = 4;
    static final int NOTE_WIDTH = 80;
    static final int NOTE_HEIGHT = 20;
    static final int HIT_LINE_Y = HEIGHT - 100;
    static final int FPS = 60;
    // Beat-locked approach time (6 beats at 149 BPM)
    static final long APPROACH_TIME_MS =
            (long)((60000.0 / 149.0) * 6);
    static final long MAX_HIT_WINDOW_MS = 250;
    static final long PERFECT_WINDOW_MS = 110;
    static final long MISS_WINDOW_MS = 250;

    // --- Engine data ---
    // --- Judgement display ---
    String judgementText = "";
    long judgementTimer = 0;
    static final long JUDGEMENT_DISPLAY_MS = 500;

    // --- Character / hit animation ---
    java.awt.Image spriteGo = null;
    java.awt.Image spriteHit = null;
    boolean hitPose = false;
    long hitPoseTimer = 0;
    static final long HIT_POSE_DURATION_MS = 150;

    int characterX = WIDTH / 2; // lateral position
    int characterY = HIT_LINE_Y - 64 - 10;
    String hitText = "";
    long hitTextTimer = 0;
    static final long HIT_TEXT_DURATION_MS = 500;

    // --- Count-in ---
    String[] beatText = {"ONE","TWO","ONE","TWO","THREE","FOUR"};
    long lastBeatTime = 0;
    int beatIndex = 0;
    static long msPerBeat = (long)(60000.0 / 149.0); // BPM
    boolean countingIn = true;
    static final long COUNT_IN_DURATION_MS = msPerBeat * 6;
    static class Note {
        long hitTimeMs;
        int lane;
        boolean hit = false;
        long spawnTimeMs;
        long approachTime;

        public Note(long hitTimeMs, int lane) {
            this.approachTime = APPROACH_TIME_MS;
            this.hitTimeMs = hitTimeMs;
            this.lane = lane;
            this.spawnTimeMs = hitTimeMs - approachTime;
        }
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

    // --- Input mapping ---
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

        // Load character sprites
        try {
            spriteGo = javax.imageio.ImageIO.read(new java.io.File("assets/sprites/sprite_go.png"));
            spriteHit = javax.imageio.ImageIO.read(new java.io.File("assets/sprites/sprite_hit.png"));
        } catch (Exception e) { e.printStackTrace(); }

        try {
            if (useAutochart && audioFile != null) {
                List<Note> loadedNotes = autoChartFromAudio(audioFile, bpm);

                long rawFirstHit = Long.MAX_VALUE;
                for (Note n : loadedNotes) {
                    if (n.hitTimeMs < rawFirstHit) rawFirstHit = n.hitTimeMs;
                }

                long shift = APPROACH_TIME_MS - rawFirstHit;
                for (Note n : loadedNotes) {
                    n.hitTimeMs += shift;
                    n.spawnTimeMs = n.hitTimeMs - APPROACH_TIME_MS;
                }

                notes = adjustLanesForSpacing(loadedNotes, 150);
                notes.sort(Comparator.comparingLong(n -> n.hitTimeMs));

                javax.sound.sampled.AudioInputStream ais =
                        javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.File(audioFile));
                audioClip = javax.sound.sampled.AudioSystem.getClip();
                audioClip.open(ais);

                long audioDelayMs = rawFirstHit < 0 ? 0 : rawFirstHit;
                startTime = System.currentTimeMillis();

                if (audioDelayMs > 0) {
                    Timer audioStartTimer = new Timer((int) audioDelayMs, evt -> {
                        if (audioClip != null && !audioClip.isRunning()) {
                            audioClip.start();
                        }
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
                Sequence sequence = MidiSystem.getSequence(new java.io.File("assets/midi/wornouttapes.mid"));
                sequencer.setSequence(sequence);
                sequencer.start();
                startTime = System.currentTimeMillis();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static List<Note> autoChartFromAudio(String file, int BPM) {
        List<Note> notes = new ArrayList<>();
        try {
            javax.sound.sampled.AudioInputStream ais = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.File(file));
            javax.sound.sampled.AudioFormat format = ais.getFormat();
            boolean isBigEndian = format.isBigEndian();
            int bytesPerSample = format.getSampleSizeInBits() / 8;
            int channels = format.getChannels();
            float sampleRate = format.getSampleRate();
            int frameSize = format.getFrameSize();

            byte[] audioBytes = ais.readAllBytes();
            int totalFrames = audioBytes.length / frameSize;

            int windowSize = (int)(sampleRate * 0.02); // 20ms
            double[] envelope = new double[totalFrames / windowSize + 1];

            for (int w = 0; w < envelope.length; w++) {
                double sum = 0;
                int count = 0;
                int frameStart = w * windowSize;
                int frameEnd = Math.min((w + 1) * windowSize, totalFrames);
                for (int f = frameStart; f < frameEnd; f++) {
                    int idx = f * frameSize;
                    int sample = 0;
                    if (bytesPerSample == 2) {
                        int low = audioBytes[idx] & 0xFF;
                        int high = audioBytes[idx + 1] & 0xFF;
                        sample = isBigEndian ? (high << 8) | low : (low << 8) | high;
                        if (sample > 32767) sample -= 65536;
                    } else if (bytesPerSample == 1) {
                        sample = (audioBytes[idx] & 0xFF) - 128;
                    }
                    sum += sample * (double) sample;
                    count++;
                }
                envelope[w] = count > 0 ? Math.sqrt(sum / count) : 0;
            }

            // Normalize
            double maxEnv = 0;
            for (double v : envelope) if (v > maxEnv) maxEnv = v;
            if (maxEnv > 0) {
                for (int i = 0; i < envelope.length; i++) envelope[i] /= maxEnv;
            }

            // === ENVELOPE DEBUG ===
            double sumEnv = 0;
            int nonZeroCount = 0;
            for (double v : envelope) {
                sumEnv += v;
                if (v > 0.0001) nonZeroCount++;
            }
            double avgEnv = envelope.length > 0 ? sumEnv / envelope.length : 0;

            double p90 = 0;
            if (envelope.length > 0) {
                double[] sorted = envelope.clone();
                java.util.Arrays.sort(sorted);
                int idx = (int) (0.9 * (sorted.length - 1));
                p90 = sorted[idx];
            }

            System.out.println("=== ENVELOPE DEBUG ===");
            System.out.println("length: " + envelope.length);
            System.out.println("max normalized: " + String.format("%.6f", maxEnv));
            System.out.println("avg normalized: " + String.format("%.6f", avgEnv));
            System.out.println("90th percentile: " + String.format("%.6f", p90));
            System.out.println("non-zero windows (>0.0001): " + nonZeroCount + " / " + envelope.length);
            System.out.println("rough song duration (seconds): " + String.format("%.1f", (envelope.length * 0.020)));

            // Peak detection - patched: lower threshold + no strict local max
            double threshold = 0.04;  // ← main tuning knob - lower = more notes, higher = cleaner
            System.out.println("Using forced threshold: " + String.format("%.6f", threshold));

            List<Integer> peaks = new ArrayList<>();
            for (int i = 1; i < envelope.length - 1; i++) {
                if (envelope[i] > threshold) {
                    peaks.add(i);
                    i += 4;  // skip ahead to reduce dense clusters
                }
            }
            System.out.println("raw peaks detected: " + peaks.size());

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

            // Min separation - reduced
            long minSep = 60;
            List<Long> filtered = new ArrayList<>();
            long last = -minSep - 1;
            for (long t : sorted) {
                if (t - last >= minSep) {
                    filtered.add(t);
                    last = t;
                }
            }

            // Random lanes + same-time fix
            Random rng = new Random(42);
            Map<Long, Set<Integer>> usedAtTime = new HashMap<>();
            for (long t : filtered) {
                int lane = rng.nextInt(LANES);
                Set<Integer> used = usedAtTime.computeIfAbsent(t, k -> new HashSet<>());
                if (used.contains(lane)) {
                    for (int l = 0; l < LANES; l++) {
                        if (!used.contains(l)) {
                            lane = l;
                            break;
                        }
                    }
                }
                used.add(lane);
                notes.add(new Note(t, lane));
            }

            System.out.println("Autochart generated " + notes.size() + " notes");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return notes;
    }

    private static List<Note> adjustLanesForSpacing(List<Note> input, long minSpacingMs) {
        input.sort(Comparator.comparingLong(n -> n.hitTimeMs));
        Map<Long, Set<Integer>> used = new HashMap<>();
        for (Note n : input) {
            Set<Integer> u = used.computeIfAbsent(n.hitTimeMs, k -> new HashSet<>());
            if (u.contains(n.lane)) {
                for (int l = 0; l < LANES; l++) {
                    if (!u.contains(l)) {
                        n.lane = l;
                        break;
                    }
                }
            }
            u.add(n.lane);
        }
        return input;
    }

    public static List<Note> loadMidi(String filename, Difficulty difficulty) {
        List<Note> loadedNotes = new ArrayList<>();
        try {
            Sequence sequence = MidiSystem.getSequence(new java.io.File(filename));
            int minPitch = Integer.MAX_VALUE;
            int maxPitch = Integer.MIN_VALUE;
            List<MidiEvent> noteEvents = new ArrayList<>();
            int resolution = sequence.getResolution();

            for (Track track : sequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    MidiMessage message = event.getMessage();
                    if (message instanceof ShortMessage) {
                        ShortMessage sm = (ShortMessage) message;
                        if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                            int pitch = sm.getData1();
                            if (pitch < minPitch) minPitch = pitch;
                            if (pitch > maxPitch) maxPitch = pitch;
                            noteEvents.add(event);
                        }
                    }
                }
            }

            if (minPitch > maxPitch) return loadedNotes;

            int pitchRange = maxPitch - minPitch + 1;
            int laneSize = Math.max(1, pitchRange / LANES);

            for (MidiEvent event : noteEvents) {
                ShortMessage sm = (ShortMessage) event.getMessage();
                int pitch = sm.getData1();
                int lane = (pitch - minPitch) / laneSize;
                if (lane >= LANES) lane = LANES - 1;
                long tick = event.getTick();
                long ms = (long) ((tick * 60000.0) / (resolution * 120));
                loadedNotes.add(new Note(ms, lane));
            }

            loadedNotes.sort(Comparator.comparingLong(n -> n.hitTimeMs));

            // --- Difficulty Filtering ---
            long minSpacing;
            int maxChordSize;

            switch (difficulty) {
                case EASY -> {
                    minSpacing = 250;
                    maxChordSize = 1;
                }
                case NORMAL -> {
                    minSpacing = 150;
                    maxChordSize = 2;
                }
                case HARD -> {
                    minSpacing = 80;
                    maxChordSize = LANES;
                }
                default -> {
                    minSpacing = 150;
                    maxChordSize = 2;
                }
            }

            // Enforce minimum spacing
            List<Note> spacingFiltered = new ArrayList<>();
            long lastTime = -9999;
            for (Note n : loadedNotes) {
                if (n.hitTimeMs - lastTime >= minSpacing) {
                    spacingFiltered.add(n);
                    lastTime = n.hitTimeMs;
                }
            }

            // Limit chord size
            Map<Long, List<Note>> byTime = new HashMap<>();
            for (Note n : spacingFiltered) {
                byTime.computeIfAbsent(n.hitTimeMs, k -> new ArrayList<>()).add(n);
            }

            List<Note> finalNotes = new ArrayList<>();
            for (List<Note> group : byTime.values()) {
                group.sort(Comparator.comparingInt(n -> n.lane));
                for (int i = 0; i < Math.min(maxChordSize, group.size()); i++) {
                    finalNotes.add(group.get(i));
                }
            }

            loadedNotes = finalNotes;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return loadedNotes;
    }

    public void updateNotes() {
        long now = getCurrentTimeMs();
        // --- Count-in logic ---
        if (countingIn) {
            long sysNow = System.currentTimeMillis();
            if (sysNow - lastBeatTime >= msPerBeat) {
                lastBeatTime = sysNow;
                System.out.println(beatText[beatIndex % beatText.length]);
                hitText = beatText[beatIndex % beatText.length];
                hitTextTimer = sysNow;
                beatIndex++;
            }
            if (sysNow - startTime >= COUNT_IN_DURATION_MS) {
                countingIn = false;
            }
        }
        for (Note n : notes) {
            if (!n.hit && now - n.hitTimeMs > MISS_WINDOW_MS) {
                n.hit = true;
                combo = 0;
                missCount++;
                System.out.println("MISS (auto)");
                printScoreStats();
                judgementText = "MISS";
                judgementTimer = System.currentTimeMillis();
                // Sprite swap and WHACK text for auto-miss
                hitPose = true;
                hitPoseTimer = System.currentTimeMillis();
                hitTextTimer = System.currentTimeMillis();
                hitText = "WHACK!\nMISS";
            }
        }
    }

    private long getCurrentTimeMs() {
        if (sequencer != null && sequencer.isOpen()) {
            return sequencer.getMicrosecondPosition() / 1000;
        } else {
            return System.currentTimeMillis() - startTime;
        }
    }

    public void paint(Graphics g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        for (int i = 0; i < LANES; i++) {
            g.setColor(Color.DARK_GRAY);
            g.fillRect(i * NOTE_WIDTH, 0, NOTE_WIDTH, HEIGHT);
        }

        long now = getCurrentTimeMs();
        double scrollSpeed = (double) (HIT_LINE_Y + NOTE_HEIGHT) / APPROACH_TIME_MS;

        for (Note n : notes) {
            if (n.hit) continue;
            if (now < n.spawnTimeMs) continue;

            int y = HIT_LINE_Y - (int) ((n.hitTimeMs - now) * scrollSpeed);
            if (y > -NOTE_HEIGHT - 300 || y < HEIGHT + 300) {
                g.setColor(Color.CYAN);
                g.fillRect(n.lane * NOTE_WIDTH, y, NOTE_WIDTH, NOTE_HEIGHT);
            }
        }

        g.setColor(Color.RED);
        g.fillRect(0, HIT_LINE_Y, WIDTH, 5);

        // Draw judgement text above hit line, fading after ~500ms
        if (!judgementText.isEmpty()) {
            long elapsed = System.currentTimeMillis() - judgementTimer;
            if (elapsed <= JUDGEMENT_DISPLAY_MS) {
                g.setColor(Color.WHITE);
                g.drawString(judgementText, WIDTH / 2 - 30, HIT_LINE_Y - 30);
            }
        }

        // --- Draw character ---
        if (spriteGo != null && spriteHit != null) {
            java.awt.Image currentSprite = hitPose ? spriteHit : spriteGo;
            int charWidth = 64;
            int charHeight = 64;
            if (hitPose && System.currentTimeMillis() - hitPoseTimer > HIT_POSE_DURATION_MS) {
                hitPose = false;
            }
            g.drawImage(currentSprite, characterX - charWidth / 2, characterY, charWidth, charHeight, null);
        }

        // --- Draw hit text ---
        if (!hitText.isEmpty()) {
            long elapsed = System.currentTimeMillis() - hitTextTimer;
            if (elapsed <= HIT_TEXT_DURATION_MS) {
                g.setColor(Color.WHITE);
                String[] lines = hitText.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    g.drawString(lines[i], characterX - 40, characterY - 10 - i * 15);
                }
            }
        }
    }

    public void keyPressed(KeyEvent e) {
        long now = getCurrentTimeMs();
        Integer lane = keyToLane.get(e.getKeyCode());
        if (lane == null) return;

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
            candidate.hit = true;
            long offset = Math.abs(candidate.hitTimeMs - now);

            combo++;
            if (combo > maxCombo) maxCombo = combo;

            if (offset <= PERFECT_WINDOW_MS) {
                perfectCount++;
                System.out.println("PERFECT (" + offset + "ms)");
            } else {
                goodCount++;
                System.out.println("GOOD (" + offset + "ms)");
            }

            // Set on-screen judgement text
            judgementText = offset <= PERFECT_WINDOW_MS ? "PERFECT" :
                            offset <= MAX_HIT_WINDOW_MS ? "GOOD" : "MISS";
            judgementTimer = System.currentTimeMillis();

            // --- Sprite swap and WHACK text ---
            hitPose = true;
            hitPoseTimer = System.currentTimeMillis();
            hitTextTimer = System.currentTimeMillis();
            hitText = (offset <= PERFECT_WINDOW_MS ? "WHACK!\nPERFECT (" + offset + "ms)" :
                       offset <= MAX_HIT_WINDOW_MS ? "WHACK!\nGOOD (" + offset + "ms)" :
                       "WHACK!\nMISS");
            if (candidate != null) characterX = candidate.lane * NOTE_WIDTH + NOTE_WIDTH / 2;

            printScoreStats();

        } else {
            combo = 0;
            missCount++;
            System.out.println("MISS (no note in window)");
            // For MISS (no candidate)
            judgementText = "MISS";
            judgementTimer = System.currentTimeMillis();
            // Sprite swap and WHACK text for miss
            hitPose = true;
            hitPoseTimer = System.currentTimeMillis();
            hitTextTimer = System.currentTimeMillis();
            hitText = "WHACK!\nMISS";
            printScoreStats();
        }
    }

    private void printScoreStats() {
        int totalHits = perfectCount + goodCount + missCount;
        if (totalHits == 0) return;

        double accuracy = ((perfectCount * 1.0) + (goodCount * 0.7)) / totalHits * 100.0;

        System.out.println(
                "Combo: " + combo +
                " | MaxCombo: " + maxCombo +
                " | Perfect: " + perfectCount +
                " | Good: " + goodCount +
                " | Miss: " + missCount +
                " | Accuracy: " + String.format("%.2f", accuracy) + "%"
        );
        System.out.println("--------------------------------------------------");
    }

    public void keyReleased(KeyEvent e) {}
    public void keyTyped(KeyEvent e) {}

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

        Timer timer = new Timer(1000 / FPS, evt -> {
            canvas.updateNotes();
            canvas.repaint();
        });
        timer.start();
    }
}