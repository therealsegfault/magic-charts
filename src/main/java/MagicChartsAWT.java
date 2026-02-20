import java.awt.Canvas;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import javax.swing.*;
import javax.swing.Timer;
import javax.sound.midi.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.sound.midi.*;

public class MagicChartsAWT extends Canvas implements KeyListener {

    // --- Config ---
    static final int WIDTH = 400;
    static final int HEIGHT = 600;
    static final int LANES = 4;
    static final int NOTE_WIDTH = 80;
    static final int NOTE_HEIGHT = 20;
    static final int HIT_LINE_Y = HEIGHT - 100;
    static final int FPS = 60;

    // --- Engine data ---
    static class Note {
        long hitTimeMs;
        int lane;
        boolean hit = false;
        long spawnTimeMs;
        long approachTime;

        public Note(long hitTimeMs, int lane) {
            this.approachTime = 2000; // approachTime = 2000 ms
            this.hitTimeMs = hitTimeMs;
            this.lane = lane;
            this.spawnTimeMs = hitTimeMs - approachTime;
            // Removed clamp to 0 to allow negative spawnTimeMs
        }
    }

    java.util.List<Note> notes = new ArrayList<>();
    Sequencer sequencer;
    javax.sound.sampled.Clip audioClip = null;
    long startTime = 0;

    // --- Input mapping ---
    Map<Integer, Integer> keyToLane = Map.of(
            KeyEvent.VK_A, 0,
            KeyEvent.VK_S, 1,
            KeyEvent.VK_D, 2,
            KeyEvent.VK_F, 3
    );

    public MagicChartsAWT() {
        this(false, null, 120); // default: use MIDI
    }

    // New constructor to allow autocharting from audio
    public MagicChartsAWT(boolean useAutochart, String audioFile, int bpm) {
        addKeyListener(this);
        setFocusable(true);
        requestFocus();
        try {
            if (useAutochart && audioFile != null) {
                // Get notes and the time of the first note (after normalization)
                java.util.List<Note> loadedNotes = autoChartFromAudio(audioFile, bpm);
                // Find the minimum hitTimeMs (the first note)
                long minHitTime = Long.MAX_VALUE;
                for (Note n : loadedNotes) {
                    if (n.hitTimeMs < minHitTime) minHitTime = n.hitTimeMs;
                }
                // Normalize so that first note is at pre-spawn buffer (e.g., 0)
                long preSpawnBuffer = 1500;
                long offset = minHitTime - preSpawnBuffer;
                if (offset > 0 && loadedNotes.size() > 0) {
                    for (Note n : loadedNotes) {
                        n.hitTimeMs -= offset;
                        n.spawnTimeMs = n.hitTimeMs - n.approachTime;
                    }
                }
                notes = adjustLanesForSpacing(loadedNotes, 150); // 150 ms minimum spacing per lane
                sequencer = null; // No MIDI sequencer
                // Prepare audio playback using Clip, and set frame position to match normalized first note
                javax.sound.sampled.AudioInputStream ais = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.File(audioFile));
                javax.sound.sampled.AudioFormat format = ais.getFormat();
                audioClip = javax.sound.sampled.AudioSystem.getClip();
                audioClip.open(ais);
                // If offset > 0, skip audio forward by offset ms to synchronize with first note
                if (offset > 0) {
                    // Calculate frame to skip to
                    long skipMicroseconds = offset * 1000;
                    // Use setMicrosecondPosition (Clip granularity is usually good enough)
                    audioClip.setMicrosecondPosition(skipMicroseconds);
                } else {
                    audioClip.setMicrosecondPosition(0);
                }
                audioClip.start();
                startTime = System.currentTimeMillis();
            } else {
                notes = loadMidi("assets/midi/MiraiGlideDotCommie.mid");
                sequencer = MidiSystem.getSequencer();
                sequencer.open();
                Sequence sequence = MidiSystem.getSequence(new java.io.File("assets/midi/MiraiGlideDotCommie.mid"));
                sequencer.setSequence(sequence);
                sequencer.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // --- Java-only autocharting from audio (WAV) ---
    public static java.util.List<Note> autoChartFromAudio(String file, int BPM) {
        java.util.List<Note> notes = new ArrayList<>();
        try {
            // Read WAV file
            javax.sound.sampled.AudioInputStream ais = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.File(file));
            javax.sound.sampled.AudioFormat format = ais.getFormat();
            boolean isBigEndian = format.isBigEndian();
            int bytesPerSample = format.getSampleSizeInBits() / 8;
            int channels = format.getChannels();
            float sampleRate = format.getSampleRate();
            int frameSize = format.getFrameSize();
            // Read all audio data into a byte array
            byte[] audioBytes = ais.readAllBytes();
            int totalFrames = audioBytes.length / frameSize;
            // Compute mono amplitude envelope (simple RMS in windows)
            int windowSize = (int)(sampleRate * 0.02); // 20ms window
            double[] envelope = new double[totalFrames / windowSize];
            for (int w = 0; w < envelope.length; w++) {
                double sum = 0;
                int count = 0;
                int frameStart = w * windowSize;
                int frameEnd = Math.min((w + 1) * windowSize, totalFrames);
                for (int f = frameStart; f < frameEnd; f++) {
                    int sampleIndex = f * frameSize;
                    // Take only first channel for mono
                    int sample = 0;
                    if (bytesPerSample == 2) {
                        int low = audioBytes[sampleIndex] & 0xFF;
                        int high = audioBytes[sampleIndex + 1] & 0xFF;
                        if (isBigEndian) {
                            sample = (high << 8) | low;
                        } else {
                            sample = (low | (high << 8));
                        }
                        // Signed 16-bit
                        if (sample > 32767) sample -= 65536;
                    } else if (bytesPerSample == 1) {
                        sample = audioBytes[sampleIndex];
                    }
                    sum += sample * sample;
                    count++;
                }
                if (count > 0) envelope[w] = Math.sqrt(sum / count);
                else envelope[w] = 0;
            }
            // Normalize envelope
            double max = 0;
            for (double v : envelope) if (v > max) max = v;
            if (max > 0) for (int i = 0; i < envelope.length; i++) envelope[i] /= max;
            // Peak detection: simple threshold and local maximum
            // Adaptive threshold based on average energy
            double avg = 0;
            for (double v : envelope) avg += v;
            avg /= Math.max(1, envelope.length);
            double threshold = Math.max(0.02, avg * 1.5);
            java.util.List<Integer> peakWindows = new ArrayList<>();
            for (int i = 1; i < envelope.length - 1; i++) {
                if (envelope[i] > threshold && envelope[i] > envelope[i-1] && envelope[i] > envelope[i+1]) {
                    peakWindows.add(i);
                }
            }
            System.out.println("Detected raw peaks: " + peakWindows.size());
            for (int i = 0; i < Math.min(10, peakWindows.size()); i++) {
                System.out.println("Peak window idx=" + peakWindows.get(i));
            }
            // Snap peaks to nearest beat (no arbitrary spacing, just strict BPM grid)
            double msPerBeat = 60000.0 / BPM;
            java.util.Set<Long> snappedTimes = new java.util.HashSet<>();
            for (int idx : peakWindows) {
                double timeMs = idx * windowSize * 1000.0 / sampleRate;
                long snapped = Math.round(timeMs / msPerBeat) * (long) msPerBeat;
                snappedTimes.add(snapped);
            }
            // Sort snapped times
            java.util.List<Long> sortedTimes = new ArrayList<>(snappedTimes);
            java.util.Collections.sort(sortedTimes);
            System.out.println("Snapped note count before filter: " + sortedTimes.size());
            for (int i = 0; i < Math.min(10, sortedTimes.size()); i++) {
                System.out.println("Snapped time " + i + " = " + sortedTimes.get(i));
            }
            // Filter notes to enforce minimum time separation (e.g., 100 ms)
            long minSeparationMs = 100;
            java.util.List<Long> filteredTimes = new ArrayList<>();
            long lastTime = -minSeparationMs - 1;
            for (long t : sortedTimes) {
                if (t - lastTime >= minSeparationMs) {
                    filteredTimes.add(t);
                    lastTime = t;
                }
            }
            // Add pre-spawn buffer so first note spawns offscreen and is hittable
            // (Normalization to 0 will be done in the constructor)
            long preSpawnBuffer = 1500;
            for (int i = 0; i < filteredTimes.size(); i++) {
                filteredTimes.set(i, filteredTimes.get(i) + preSpawnBuffer);
            }
            // Assign lanes: randomly distributed for a bit more variety
            java.util.List<Integer> lanes = new ArrayList<>();
            java.util.Random rng = new java.util.Random(0);
            for (int i = 0; i < filteredTimes.size(); i++) {
                lanes.add(rng.nextInt(LANES));
            }
            // Adjust lanes to prevent exact-lane collision at same hit time
            Map<Long, Set<Integer>> usedLanesAtTime = new HashMap<>();
            for (int i = 0; i < filteredTimes.size(); i++) {
                long t = filteredTimes.get(i);
                int assignedLane = lanes.get(i);
                Set<Integer> used = usedLanesAtTime.getOrDefault(t, new HashSet<>());
                if (used.contains(assignedLane)) {
                    // Find next available lane for this time
                    for (int l = 0; l < LANES; l++) {
                        if (!used.contains(l)) {
                            assignedLane = l;
                            break;
                        }
                    }
                }
                used.add(assignedLane);
                usedLanesAtTime.put(t, used);
                notes.add(new Note(t, assignedLane));
            }
            // Debug print for first few notes
            System.out.println("Final autochart notes: " + notes.size());
            for (int i = 0; i < Math.min(10, notes.size()); i++) {
                Note n = notes.get(i);
                System.out.println("Note " + i + " -> time=" + n.hitTimeMs + " lane=" + n.lane);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return notes;
    }

    // Adjust lanes only to prevent exact-lane collisions at the same hit time (does not modify hit times or compress spacing).
    private static java.util.List<Note> adjustLanesForSpacing(java.util.List<Note> inputNotes, long minSpacingMs) {
        // Sort notes by hitTimeMs
        inputNotes.sort(Comparator.comparingLong(n -> n.hitTimeMs));
        Map<Long, Set<Integer>> usedLanesAtTime = new HashMap<>();
        for (Note n : inputNotes) {
            Set<Integer> used = usedLanesAtTime.getOrDefault(n.hitTimeMs, new HashSet<>());
            int origLane = n.lane;
            int assignedLane = origLane;
            if (used.contains(origLane)) {
                // Find next available lane for this time
                for (int l = 0; l < LANES; l++) {
                    if (!used.contains(l)) {
                        assignedLane = l;
                        break;
                    }
                }
            }
            n.lane = assignedLane;
            used.add(assignedLane);
            usedLanesAtTime.put(n.hitTimeMs, used);
        }
        return inputNotes;
    }

    public static java.util.List<Note> loadMidi(String filename) {
        java.util.List<Note> loadedNotes = new ArrayList<>();
        try {
            Sequence sequence = MidiSystem.getSequence(new java.io.File(filename));
            int minPitch = Integer.MAX_VALUE;
            int maxPitch = Integer.MIN_VALUE;
            List<MidiEvent> noteEvents = new ArrayList<>();
            int resolution = sequence.getResolution();

            // First pass: find min and max pitch
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

            if (minPitch > maxPitch) {
                // No notes found, return empty list
                return loadedNotes;
            }

            // Calculate pitch range and lane size
            int pitchRange = maxPitch - minPitch + 1;
            int laneSize = Math.max(1, pitchRange / LANES);

            for (MidiEvent event : noteEvents) {
                ShortMessage sm = (ShortMessage) event.getMessage();
                int pitch = sm.getData1();
                int lane = (pitch - minPitch) / laneSize;
                if (lane >= LANES) lane = LANES - 1; // Clamp lane to max
                long tick = event.getTick();
                // Convert tick to milliseconds
                long ms = (long)((tick * 60000.0) / (resolution * 120)); // assuming 120 bpm
                loadedNotes.add(new Note(ms, lane));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return loadedNotes;
    }

    public void updateNotes() {
        long now;
        if (sequencer != null && sequencer.isOpen()) {
            now = sequencer.getMicrosecondPosition() / 1000;
        } else {
            now = System.currentTimeMillis() - startTime;
        }
        int missWindow = 500; // ms after hit line to mark miss
        for (Note n : notes) {
            if (!n.hit) {
                // Only mark as missed if note has spawned (now >= n.spawnTimeMs) and has passed the hit line plus the miss window
                if (now >= n.spawnTimeMs && (now - n.hitTimeMs > missWindow)) {
                    System.out.println("Lane " + n.lane + ": MISS");
                    n.hit = true;
                }
            }
        }
    }

    public void paint(Graphics g) {
        g.setColor(Color.BLACK);
        g.fillRect(0,0,WIDTH,HEIGHT);

        // Draw lanes
        for (int i=0;i<LANES;i++) {
            g.setColor(Color.DARK_GRAY);
            g.fillRect(i*NOTE_WIDTH,0,NOTE_WIDTH,HEIGHT);
        }

        // Draw notes
        long now = 0;
        if (sequencer != null && sequencer.isOpen()) {
            now = sequencer.getMicrosecondPosition() / 1000;
        } else {
            now = System.currentTimeMillis() - startTime;
        }
        // Improved note scrolling: scale Y position by scrollSpeedFactor, draw only visible notes
        double scrollSpeedFactor = (double)(HIT_LINE_Y + NOTE_HEIGHT) / 2000.0; // 2000 ms approach time
        int visibleMargin = 200; // pixels above and below hit line to consider visible
        for (Note n : notes) {
            if (!n.hit) {
                g.setColor(Color.CYAN);
                // Only draw notes that have spawned (i.e., now >= n.spawnTimeMs)
                if (now >= n.spawnTimeMs) {
                    int y = HIT_LINE_Y - (int)((n.hitTimeMs - now) * scrollSpeedFactor);
                    // Only draw notes that are within a visible range above and below the hit line
                    if (y > -NOTE_HEIGHT - visibleMargin && y < HEIGHT + visibleMargin) {
                        g.fillRect(n.lane * NOTE_WIDTH, y, NOTE_WIDTH, NOTE_HEIGHT);
                    }
                }
            }
        }

        // Draw hit line
        g.setColor(Color.RED);
        g.fillRect(0, HIT_LINE_Y, WIDTH, 5);
    }

    public void keyPressed(KeyEvent e) {
        long now = 0;
        if (sequencer != null && sequencer.isOpen()) {
            now = sequencer.getMicrosecondPosition() / 1000;
        } else {
            now = System.currentTimeMillis() - startTime;
        }
        Integer lane = keyToLane.get(e.getKeyCode());
        if (lane == null) return;

        Note closest = null;
        long minOffset = Long.MAX_VALUE;
        for (Note n : notes) {
            if (!n.hit && n.lane == lane) {
                long offset = Math.abs(n.hitTimeMs - now);
                if (offset < minOffset) {
                    minOffset = offset;
                    closest = n;
                }
            }
        }

        if (closest != null) {
            closest.hit = true;
            if (minOffset <= 110) System.out.println("Lane " + lane + ": PERFECT (" + minOffset + "ms)");
            else if (minOffset <= 250) System.out.println("Lane " + lane + ": GOOD (" + minOffset + "ms)");
            else System.out.println("Lane " + lane + ": MISS (" + minOffset + "ms)");
        } else {
            System.out.println("Lane " + lane + ": MISS (no note)");
        }
    }

    public void keyReleased(KeyEvent e) {}
    public void keyTyped(KeyEvent e) {}

    public static void main(String[] args) {
        JFrame frame = new JFrame("MagicCharts AWT");
        // To use autocharting from audio, set useAutochart to true and provide WAV file and BPM
        boolean useAutochart = true;
        String audioFile = "assets/songs/MiraiGlideDotCommie.wav"; // Change as needed
        int bpm = 140;
        MagicChartsAWT canvas = new MagicChartsAWT(useAutochart, audioFile, bpm);
        canvas.setSize(WIDTH,HEIGHT);
        frame.add(canvas);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        // Add a window listener to stop and close sequencer on close
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (canvas.sequencer != null && canvas.sequencer.isOpen()) {
                    canvas.sequencer.stop();
                    canvas.sequencer.close();
                }
                if (canvas.audioClip != null && canvas.audioClip.isOpen()) {
                    canvas.audioClip.stop();
                    canvas.audioClip.close();
                }
            }
        });

        // Main update loop
        Timer timer = new Timer(1000/FPS, e -> {
            canvas.updateNotes();
            canvas.repaint();
        });
        timer.start();
    }
}