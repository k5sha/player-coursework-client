package ua.kpi.musicplayer.audio;

import ua.kpi.musicplayer.model.Track;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
 import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;


public class SimpleAudioPlayer {

    private static final int BUFFER_SIZE = 8 * 1024;

    private final Object pauseLock = new Object();
    private final Equalizer equalizer = new Equalizer();

    private volatile boolean playing;
    private volatile boolean paused;
    private volatile float volume = 1.0f;
    private volatile Runnable onTrackFinished;
    private Thread playbackThread;
    private SourceDataLine line;

    public void play(Track track) {
        play(Path.of(track.getLocation()));
    }

    public void play(Path file) {
        stop();
        try {
            AudioInputStream input = AudioSystem.getAudioInputStream(file.toFile());
            AudioFormat format = input.getFormat();
            if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                AudioFormat pcm = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        format.getSampleRate(),
                        16,
                        format.getChannels(),
                        format.getChannels() * 2,
                        format.getSampleRate(),
                        false);
                input = AudioSystem.getAudioInputStream(pcm, input);
                format = pcm;
            }

            SourceDataLine newLine = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, format));
            newLine.open(format);
            applyVolume(newLine);
            equalizer.resetState();

            line = newLine;
            playing = true;
            paused = false;

            AudioInputStream audioStream = input;
            AudioFormat playbackFormat = format;
            playbackThread = new Thread(() -> playLoop(newLine, audioStream, playbackFormat), "Audio playback");
            playbackThread.setDaemon(true);
            playbackThread.start();
        } catch (UnsupportedAudioFileException | LineUnavailableException | IOException e) {
            throw new IllegalStateException("Не вдалося відтворити файл: " + file, e);
        }
    }

    public void stop() {
        playing = false;
        paused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        SourceDataLine currentLine = line;
        try {
            if (currentLine != null && currentLine.isOpen()) {
                currentLine.stop();
                currentLine.flush();
            }
        } catch (IllegalStateException ignored) {
            // Лінію вже закрито потоком відтворення
        }

        Thread thread = playbackThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        playbackThread = null;
    }

    public void pause() {
        if (!playing || paused) {
            return;
        }

        paused = true;
        SourceDataLine currentLine = line;
        try {
            if (currentLine != null && currentLine.isOpen()) {
                currentLine.stop();
            }
        } catch (IllegalStateException ignored) {
            // Лінію вже закрито потоком відтворення
        }
    }

    public void resume() {
        if (!playing || !paused) {
            return;
        }

        paused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        SourceDataLine currentLine = line;
        try {
            if (currentLine != null && currentLine.isOpen()) {
                currentLine.start();
            }
        } catch (IllegalStateException ignored) {
            // Лінію вже закрито потоком відтворення
        }
    }

    public void togglePause() {
        if (paused) {
            resume();
        } else {
            pause();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        applyVolume(line);
    }

    public float getVolume() {
        return volume;
    }

    public Equalizer getEqualizer() {
        return equalizer;
    }

    public void setOnTrackFinished(Runnable onTrackFinished) {
        this.onTrackFinished = onTrackFinished;
    }

    private void applyVolume(SourceDataLine dataLine) {
        if (dataLine == null || !dataLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }

        FloatControl gain = (FloatControl) dataLine.getControl(FloatControl.Type.MASTER_GAIN);
        float decibels = volume <= 0.0f
                ? gain.getMinimum()
                : (float) (20.0 * Math.log10(volume));
        decibels = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels));
        try {
            gain.setValue(decibels);
        } catch (IllegalArgumentException ignored) {
            // Залишаємо попередню гучність
        }
    }

    private void playLoop(SourceDataLine dataLine, AudioInputStream input, AudioFormat format) {
        byte[] buffer = new byte[BUFFER_SIZE];
        boolean finishedNaturally = false;
        try {
            if (!paused) {
                dataLine.start();
            }
            int read;
            while (playing) {
                read = input.read(buffer, 0, buffer.length);
                if (read == -1) {
                    finishedNaturally = playing;
                    break;
                }
                equalizer.process(buffer, read, format);
                awaitResume();
                if (!playing) {
                    break;
                }
                dataLine.write(buffer, 0, read);
            }
            if (playing) {
                dataLine.drain();
            }
        } catch (IOException | IllegalStateException ignored) {
            // Відтворення зупинено викликом stop()
        } finally {
            playing = false;
            paused = false;
            closeQuietly(input);
            dataLine.close();
        }

        if (finishedNaturally) {
            Runnable callback = onTrackFinished;
            if (callback != null) {
                callback.run();
            }
        }
    }

    private void awaitResume() {
        synchronized (pauseLock) {
            while (playing && paused) {
                try {
                    pauseLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void closeQuietly(AudioInputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // нічого не робимо
        }
    }
}
