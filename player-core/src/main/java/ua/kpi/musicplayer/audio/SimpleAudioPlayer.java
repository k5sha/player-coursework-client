package ua.kpi.musicplayer.audio;

import ua.kpi.musicplayer.model.Track;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;


public class SimpleAudioPlayer {

    private static final int BUFFER_SIZE = 8 * 1024;

    private volatile boolean playing;
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

            line = newLine;
            playing = true;

            AudioInputStream audioStream = input;
            playbackThread = new Thread(() -> playLoop(newLine, audioStream), "Audio playback");
            playbackThread.setDaemon(true);
            playbackThread.start();
        } catch (UnsupportedAudioFileException | LineUnavailableException | IOException e) {
            throw new IllegalStateException("Не вдалося відтворити файл: " + file, e);
        }
    }

    public void stop() {
        playing = false;

        SourceDataLine currentLine = line;
        try {
            if (currentLine != null && currentLine.isOpen()) {
                currentLine.stop();
                currentLine.flush();
            }
        } catch (IllegalStateException ignored) {
            // Лінію вже закрито потоком відтворення.
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

    private void playLoop(SourceDataLine dataLine, AudioInputStream input) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            dataLine.start();
            int read;
            while (playing && (read = input.read(buffer, 0, buffer.length)) != -1) {
                dataLine.write(buffer, 0, read);
            }
            if (playing) {
                dataLine.drain();
            }
        } catch (IOException | IllegalStateException ignored) {
            // Відтворення зупинено викликом stop().
        } finally {
            playing = false;
            closeQuietly(input);
            dataLine.close();
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
