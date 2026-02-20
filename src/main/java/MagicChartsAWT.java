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

        public Note(long hitTimeMs, int lane) {
            this.hitTimeMs = hitTimeMs;
            this.lane = lane;
        }
    }

    java.util.List<Note> notes = new ArrayList<>();
    Sequencer sequencer;

    // --- Input mapping ---
    Map<Integer, Integer> keyToLane = Map.of(
            KeyEvent.VK_A, 0,
            KeyEvent.VK_S, 1,
            KeyEvent.VK_D, 2,
            KeyEvent.VK_F, 3
    );

    public MagicChartsAWT() {
        addKeyListener(this);
        setFocusable(true);
        requestFocus();
        try {
            notes = loadMidi("assets/midi/MiraiGlideDotCommie.mid");
            sequencer = MidiSystem.getSequencer();
            sequencer.open();
            Sequence sequence = MidiSystem.getSequence(new java.io.File("assets/midi/MiraiGlideDotCommie.mid"));
            sequencer.setSequence(sequence);
            sequencer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
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
        if (sequencer == null || !sequencer.isOpen()) return;
        long now = sequencer.getMicrosecondPosition() / 1000;
        for (Note n : notes) {
            if (!n.hit && now > n.hitTimeMs + 500) { // missed
                System.out.println("Lane " + n.lane + ": MISS");
                n.hit = true;
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
        }
        for (Note n : notes) {
            if (!n.hit) {
                g.setColor(Color.CYAN);
                int y = HIT_LINE_Y - (int)(n.hitTimeMs - now)/2; // simple scaling
                g.fillRect(n.lane*NOTE_WIDTH, y, NOTE_WIDTH, NOTE_HEIGHT);
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
        MagicChartsAWT canvas = new MagicChartsAWT();
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