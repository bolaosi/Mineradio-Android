package com.mineradio.android.playback;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.AudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Pass-through PCM processor: analysis never changes or synthesizes playback samples. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class SpectrumProcessor extends BaseAudioProcessor {
    volatile boolean visible;
    volatile PcmAnalysis.Frame latest;
    volatile int sampleRate = 44100;
    private final float[] samples = new float[PcmAnalysis.FFT_SIZE];
    private int filled;
    private long lastAnalysis;

    @Override protected AudioFormat onConfigure(AudioFormat input) throws AudioProcessor.UnhandledAudioFormatException {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw new AudioProcessor.UnhandledAudioFormatException(input);
        sampleRate = input.sampleRate;
        return input;
    }
    @Override public void queueInput(ByteBuffer input) {
        // Media3 also feeds the shared EMPTY_BUFFER to drain this stage. Allocating an empty
        // output reuses that same sentinel, so put(input) would attempt a forbidden self-copy.
        // Leave any pending PCM output untouched when there is nothing new to consume.
        if (!input.hasRemaining()) return;
        ByteBuffer output = replaceOutputBuffer(input.remaining());
        if (visible) {
            ByteBuffer pcm = input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int channels = inputAudioFormat.channelCount;
            while (pcm.remaining() >= 2*channels) {
                float sum = 0; for(int c=0;c<channels;c++) sum += pcm.getShort()/32768f;
                samples[filled++] = sum/channels;
                if(filled==samples.length) {
                    filled=0; long now=System.nanoTime();
                    if(now-lastAnalysis>=80_000_000L) {latest=PcmAnalysis.analyze(samples,sampleRate);lastAnalysis=now;}
                }
            }
        }
        output.put(input).flip();
    }
    @Override protected void onFlush() { filled=0; latest=null; }
}
