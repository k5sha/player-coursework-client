package ua.kpi.musicplayer.audio;

import javax.sound.sampled.AudioFormat;

public class Equalizer {

    public static final int LOW_BAND = 0;
    public static final int MID_BAND = 1;
    public static final int HIGH_BAND = 2;
    public static final int BAND_COUNT = 3;

    public static final float MIN_GAIN_DB = -12.0f;
    public static final float MAX_GAIN_DB = 12.0f;

    private static final double LOW_ALPHA = 0.08;
    private static final double HIGH_ALPHA = 0.35;

    public enum Preset {
        FLAT("Плоский", 0, 0, 0),
        BASS("Бас", 6, 0, -2),
        VOCAL("Вокал", -2, 4, 0),
        TREBLE("Високі", -2, 0, 6);

        private final String displayName;
        private final float lowDb;
        private final float midDb;
        private final float highDb;

        Preset(String displayName, float lowDb, float midDb, float highDb) {
            this.displayName = displayName;
            this.lowDb = lowDb;
            this.midDb = midDb;
            this.highDb = highDb;
        }

        public String getDisplayName() {
            return displayName;
        }

        public float getGainDb(int band) {
            switch (band) {
                case LOW_BAND:
                    return lowDb;
                case MID_BAND:
                    return midDb;
                case HIGH_BAND:
                    return highDb;
                default:
                    throw new IllegalArgumentException("Невідома смуга еквалайзера: " + band);
            }
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private volatile float[] bandGainsDb = {0.0f, 0.0f, 0.0f};

    private final double[] lowState = new double[2];
    private final double[] highState = new double[2];

    public void setBandGainDb(int band, float gainDb) {
        checkBand(band);
        float[] updated = bandGainsDb.clone();
        updated[band] = Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, gainDb));
        bandGainsDb = updated;
    }

    public float getBandGainDb(int band) {
        checkBand(band);
        return bandGainsDb[band];
    }

    public void applyPreset(Preset preset) {
        if (preset == null) {
            return;
        }
        bandGainsDb = new float[]{
                preset.getGainDb(LOW_BAND),
                preset.getGainDb(MID_BAND),
                preset.getGainDb(HIGH_BAND)
        };
    }

    public void reset() {
        applyPreset(Preset.FLAT);
    }

    public boolean isFlat() {
        float[] gains = bandGainsDb;
        return gains[LOW_BAND] == 0.0f && gains[MID_BAND] == 0.0f && gains[HIGH_BAND] == 0.0f;
    }

    public void resetState() {
        for (int channel = 0; channel < lowState.length; channel++) {
            lowState[channel] = 0.0;
            highState[channel] = 0.0;
        }
    }

    public void process(byte[] buffer, int length, AudioFormat format) {
        if (buffer == null || length <= 0 || format == null || isFlat()) {
            return;
        }
        if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                || format.getSampleSizeInBits() != 16) {
            return;
        }

        float[] gains = bandGainsDb;
        double lowGain = Math.pow(10.0, gains[LOW_BAND] / 20.0);
        double midGain = Math.pow(10.0, gains[MID_BAND] / 20.0);
        double highGain = Math.pow(10.0, gains[HIGH_BAND] / 20.0);

        int channels = Math.max(1, Math.min(2, format.getChannels()));
        int frameSize = channels * 2;
        boolean bigEndian = format.isBigEndian();

        for (int frame = 0; frame + frameSize <= length; frame += frameSize) {
            for (int channel = 0; channel < channels; channel++) {
                int index = frame + channel * 2;
                int raw = bigEndian
                        ? (short) ((buffer[index] << 8) | (buffer[index + 1] & 0xFF))
                        : (short) ((buffer[index] & 0xFF) | (buffer[index + 1] << 8));

                double sample = raw / 32768.0;

                double low = lowState[channel] + LOW_ALPHA * (sample - lowState[channel]);
                lowState[channel] = low;

                double rest = sample - low;
                double high = highState[channel] + HIGH_ALPHA * (rest - highState[channel]);
                highState[channel] = high;

                double mid = rest - high;
                double value = low * lowGain + mid * midGain + high * highGain;

                int processed = (int) Math.round(Math.max(-1.0, Math.min(1.0, value)) * 32767);
                if (bigEndian) {
                    buffer[index] = (byte) (processed >> 8);
                    buffer[index + 1] = (byte) processed;
                } else {
                    buffer[index] = (byte) processed;
                    buffer[index + 1] = (byte) (processed >> 8);
                }
            }
        }
    }

    private static void checkBand(int band) {
        if (band < 0 || band >= BAND_COUNT) {
            throw new IllegalArgumentException("Невідома смуга еквалайзера: " + band);
        }
    }
}
