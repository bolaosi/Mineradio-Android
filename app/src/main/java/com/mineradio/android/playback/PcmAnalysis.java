package com.mineradio.android.playback;

/** Radix-2 FFT from actual decoded PCM, without microphone access. Pure Java for verification. */
public final class PcmAnalysis {
    public static final int FFT_SIZE = 512;
    public static final class Frame {
        public final int[] spectrum, waveform;
        public final double rms;
        Frame(int[] spectrum, int[] waveform, double rms) { this.spectrum=spectrum; this.waveform=waveform; this.rms=rms; }
    }
    public static Frame analyze(float[] samples, int sampleRate) {
        double[] re = new double[FFT_SIZE], im = new double[FFT_SIZE];
        double square = 0;
        int[] waveform = new int[128];
        for (int i=0; i<FFT_SIZE; i++) {
            double value = Math.max(-1, Math.min(1, samples[i])); square += value*value;
            re[i] = value * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE-1)));
            if (i%4==0) waveform[i/4] = (int)Math.round(128 + value*127);
        }
        for (int i=1,j=0; i<FFT_SIZE; i++) {
            int bit=FFT_SIZE>>1; for (;(j&bit)!=0;bit>>=1) j^=bit; j^=bit;
            if(i<j){ double t=re[i];re[i]=re[j];re[j]=t; }
        }
        for(int length=2;length<=FFT_SIZE;length<<=1){
            double angle=-2*Math.PI/length;
            for(int start=0;start<FFT_SIZE;start+=length){
                for(int j=0;j<length/2;j++){
                    double c=Math.cos(angle*j),s=Math.sin(angle*j);
                    int a=start+j,b=a+length/2;
                    double tr=re[b]*c-im[b]*s,ti=re[b]*s+im[b]*c;
                    re[b]=re[a]-tr;im[b]=im[a]-ti;re[a]+=tr;im[a]+=ti;
                }
            }
        }
        int[] bins = new int[64];
        // Log bands cover 20Hz..20kHz. Values are dBFS mapped from -80..0dB to 0..255.
        for(int band=0;band<64;band++){
            double low=20*Math.pow(1000,band/64.0),high=20*Math.pow(1000,(band+1)/64.0),magnitude=0;
            int first=Math.max(1,(int)Math.ceil(low*FFT_SIZE/sampleRate));
            int last=Math.min(FFT_SIZE/2,(int)Math.ceil(high*FFT_SIZE/sampleRate));
            if (first>FFT_SIZE/2) continue;
            for(int k=first;k<=Math.max(first,last);k++) magnitude=Math.max(magnitude,Math.hypot(re[k],im[k])*4/FFT_SIZE);
            double db=20*Math.log10(Math.max(0.0001,magnitude));
            bins[band]=(int)Math.round(Math.max(0,Math.min(255,(db+80)/80*255)));
        }
        return new Frame(bins,waveform,Math.sqrt(square/FFT_SIZE));
    }
}
