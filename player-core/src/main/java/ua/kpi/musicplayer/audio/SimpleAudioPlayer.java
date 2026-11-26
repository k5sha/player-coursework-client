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
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;


public class SimpleAudioPlayer {

    private static final int BUFFER_SIZE = 8 * 1024;

    private final Object pauseLock = new Object();
    private final Equalizer equalizer = new Equalizer();
    private final AtomicLong playRequest = new AtomicLong();

    private volatile boolean playing;
    private volatile boolean paused;
    private volatile float volume = 1.0f;
    private volatile Runnable onTrackFinished;
    private volatile long positionMs;
    private volatile long durationMs;
    private volatile boolean canSeek;
    private volatile Path currentFile;
    private volatile Path preparedFile;
    private volatile long preparedPositionMs;
    private volatile Thread playbackThread;
    private SourceDataLine line;

    public void play(Track track) {
        play(Path.of(track.getLocation()));
    }

    public void play(Path file) {
        playRequest.incrementAndGet();
        stopPlayback();

        currentFile = file;
        canSeek = true;
        positionMs = 0;
        durationMs = 0;

        long startPositionMs = 0;
        if (file.equals(preparedFile)) {
            startPositionMs = preparedPositionMs;
        }
        preparedFile = null;
        preparedPositionMs = 0;

        AudioInputStream input = null;
        try {
            input = openPcmStream(file);
            durationMs = calculateDurationMs(input);
            startPositionMs = clampPositionMs(startPositionMs);
            positionMs = startPositionMs;
            skipToPosition(input, startPositionMs);
            startPlayback(input, startPositionMs, false);
        } catch (UnsupportedAudioFileException | LineUnavailableException | IOException e) {
            closeQuietly(input);
            canSeek = false;
            currentFile = null;
            throw new IllegalStateException("Не вдалося відтворити файл: " + file, e);
        }
    }

    public boolean playUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL потоку не задано");
        }

        String streamUrl = url.trim();
        long request = playRequest.incrementAndGet();
        stopPlayback();

        currentFile = null;
        preparedFile = null;
        preparedPositionMs = 0;
        canSeek = false;
        positionMs = 0;
        durationMs = 0;

        AudioInputStream input = null;
        try {
            input = AudioSystem.getAudioInputStream(URI.create(streamUrl).toURL());
            if (playRequest.get() != request) {
                closeQuietly(input);
                return false;
            }
            input = toPcmStream(input);
            startPlayback(input, 0, false);
            return true;
        } catch (IllegalArgumentException e) {
            closeQuietly(input);
            throw new IllegalStateException("Некоректний URL потоку: " + streamUrl, e);
        } catch (UnsupportedAudioFileException | LineUnavailableException | IOException e) {
            closeQuietly(input);
            throw new IllegalStateException("Не вдалося відтворити потік: " + streamUrl, e);
        }
    }

    private AudioInputStream openPcmStream(Path file)
            throws UnsupportedAudioFileException, IOException {
        return toPcmStream(AudioSystem.getAudioInputStream(file.toFile()));
    }

    private AudioInputStream toPcmStream(AudioInputStream input)
            throws UnsupportedAudioFileException, IOException {
        AudioFormat format = input.getFormat();
        if (format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED) {
            return input;
        }

        AudioFormat pcm = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                format.getSampleRate(),
                16,
                format.getChannels(),
                format.getChannels() * 2,
                format.getSampleRate(),
                false);
        return AudioSystem.getAudioInputStream(pcm, input);
    }

    private long calculateDurationMs(AudioInputStream input) {
        long frameLength = input.getFrameLength();
        float frameRate = input.getFormat().getFrameRate();
        if (frameLength <= 0 || frameRate <= 0) {
            return 0;
        }
        return (long) (frameLength * 1000.0 / frameRate);
    }

    private void startPlayback(AudioInputStream input, long startPositionMs, boolean startPaused)
            throws LineUnavailableException {
        AudioFormat format = input.getFormat();
        SourceDataLine newLine = (SourceDataLine) AudioSystem.getLine(
                new DataLine.Info(SourceDataLine.class, format));
        newLine.open(format);
        applyVolume(newLine);
        equalizer.resetState();

        line = newLine;
        playing = true;
        paused = startPaused;
        positionMs = startPositionMs;

        AudioInputStream audioStream = input;
        AudioFormat playbackFormat = format;
        Thread thread = new Thread(
                () -> playLoop(newLine, audioStream, playbackFormat, startPositionMs),
                "Audio playback");
        thread.setDaemon(true);
        playbackThread = thread;
        thread.start();
    }

    public void stop() {
        playRequest.incrementAndGet();
        stopPlayback();

        currentFile = null;
        preparedFile = null;
        preparedPositionMs = 0;
        canSeek = false;
        positionMs = 0;
        durationMs = 0;
    }

    private void stopPlayback() {
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

    public void seekToSeconds(double seconds) {
        if (!canSeek) {
            return;
        }

        long targetMs = clampPositionMs(Math.max(0, Math.round(seconds * 1000.0)));
        positionMs = targetMs;

        Path file = currentFile;
        if (file == null) {
            if (preparedFile != null) {
                preparedPositionMs = targetMs;
            }
            return;
        }
        if (!playing) {
            preparedFile = file;
            preparedPositionMs = targetMs;
            return;
        }

        boolean wasPaused = paused;
        playRequest.incrementAndGet();
        stopPlayback();

        AudioInputStream input = null;
        try {
            input = openPcmStream(file);
            long resolvedDurationMs = calculateDurationMs(input);
            if (resolvedDurationMs > 0) {
                durationMs = resolvedDurationMs;
            }
            targetMs = clampPositionMs(targetMs);
            positionMs = targetMs;
            skipToPosition(input, targetMs);
            startPlayback(input, targetMs, wasPaused);
        } catch (UnsupportedAudioFileException | LineUnavailableException | IOException e) {
            closeQuietly(input);
            throw new IllegalStateException("Не вдалося перемотати трек", e);
        }
    }

    public void preparePosition(Track track, double seconds) {
        if (track == null || track.getLocation() == null || track.getLocation().isBlank()) {
            preparedFile = null;
            preparedPositionMs = 0;
            positionMs = 0;
            canSeek = false;
            return;
        }

        preparedFile = Path.of(track.getLocation());
        preparedPositionMs = Math.max(0, Math.round(seconds * 1000.0));
        positionMs = preparedPositionMs;
        durationMs = 0;
        canSeek = true;
    }

    public double getPositionSeconds() {
        return positionMs / 1000.0;
    }

    public double getDurationSeconds() {
        return durationMs / 1000.0;
    }

    public boolean canSeek() {
        return canSeek;
    }

    private long clampPositionMs(long value) {
        if (value < 0) {
            return 0;
        }
        if (durationMs > 0 && value > durationMs) {
            return durationMs;
        }
        return value;
    }

    private void skipToPosition(AudioInputStream input, long positionMs) throws IOException {
        AudioFormat format = input.getFormat();
        int frameSize = format.getFrameSize();
        float frameRate = format.getFrameRate();
        if (positionMs <= 0 || frameSize <= 0 || frameRate <= 0) {
            return;
        }

        long bytesPerSecond = (long) (frameRate * frameSize);
        long bytesToSkip = positionMs * bytesPerSecond / 1000;
        bytesToSkip -= bytesToSkip % frameSize;

        byte[] discard = new byte[BUFFER_SIZE];
        long remaining = bytesToSkip;
        while (remaining > 0) {
            int read = input.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (read < 0) {
                return;
            }
            remaining -= read;
        }
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

    private void playLoop(SourceDataLine dataLine, AudioInputStream input, AudioFormat format,
                          long startPositionMs) {
        byte[] buffer = new byte[BUFFER_SIZE];
        long bytesPerSecond = (long) (format.getFrameRate() * format.getFrameSize());
        long processedBytes = 0;
        boolean finishedNaturally = false;
        try {
            if (!paused) {
                dataLine.start();
            }
            int read;
            while (playing && playbackThread == Thread.currentThread()) {
                read = input.read(buffer, 0, buffer.length);
                if (read == -1) {
                    finishedNaturally = playing;
                    break;
                }
                equalizer.process(buffer, read, format);
                awaitResume();
                if (!playing || playbackThread != Thread.currentThread()) {
                    break;
                }
                dataLine.write(buffer, 0, read);
                processedBytes += read;
                if (canSeek && bytesPerSecond > 0 && playbackThread == Thread.currentThread()) {
                    long currentPositionMs = startPositionMs + processedBytes * 1000L / bytesPerSecond;
                    positionMs = durationMs > 0
                            ? Math.min(currentPositionMs, durationMs)
                            : currentPositionMs;
                }
            }
            if (playing) {
                dataLine.drain();
            }
        } catch (IOException | IllegalStateException ignored) {
            // Відтворення зупинено викликом stop()
        } finally {
            boolean active = playbackThread == Thread.currentThread();
            if (active) {
                playing = false;
                paused = false;
                if (finishedNaturally && durationMs > 0) {
                    positionMs = durationMs;
                }
            }
            closeQuietly(input);
            dataLine.close();

            if (finishedNaturally && active) {
                Runnable callback = onTrackFinished;
                if (callback != null) {
                    callback.run();
                }
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
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // нічого не робимо
        }
    }
}
