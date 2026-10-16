package dev.denza.vehiclecontrol.probe;

import java.util.Arrays;

/**
 * 10ch interleaved PCM -> bounded utterances. No channel is labelled as a seat.
 *
 * All six microphone candidates are kept for the whole utterance and the one with the most voiced
 * energy is returned: the loudest channel in the first 20 ms is often not the speaker's. A 0.4 s
 * pre-roll keeps the soft start of «привет». The last 8 s of every candidate stay in a ring so a
 * command spoken while the call is still being recognised is not lost.
 */
final class CabinSpeech {
    static final int[] MICS={0,1,4,5,8,9};
    static final int FRAME=320, PRE=6400, MAX=80000, END_QUIET=11200, MIN_VOICED=4800, RECENT=128000;
    /** startSample/endSample count per-channel samples since the gate was created. */
    record Utterance(short[] pcm,double[] energy,int microphone,int voicedSamples,long startSample,long endSample) {
        Utterance(short[] pcm,double[] energy,int microphone,int voicedSamples){this(pcm,energy,microphone,voicedSamples,0,pcm.length);}
    }
    private final short[][] pre=new short[6][PRE];
    private final short[] frame=new short[FRAME*10];
    private final short[][] speech=new short[6][MAX];
    private final short[][] recent=new short[6][RECENT];
    private final double[] energy=new double[6];
    private int fill,ring,available,used,quiet,voiced;
    private boolean active;
    private long samples,startSample;
    private double noise=12;
    private boolean warming=true;
    private int warmFrames;
    double level;
    double[] levels=new double[6];
    boolean speaking(){return active;}
    int strongest;
    static short[] normalize(short[] input) {
        int peak=0;for(short value:input)peak=Math.max(peak,Math.abs((int)value));
        double gain=peak==0?1:Math.min(8,12000.0/peak);
        short[] out=new short[input.length];for(int i=0;i<out.length;i++)out[i]=(short)Math.round(input[i]*gain);return out;
    }
    void resetUtterance(){active=false;used=0;quiet=0;available=0;voiced=0;}
    /** Samples processed so far, per channel. */
    long samples(){return samples;}
    /** One microphone from {@code from} up to now, at most the last 8 s. Capture thread only. */
    short[] since(long from,int microphone) {
        int index=Arrays.binarySearch(MICS,microphone);if(index<0)throw new IllegalArgumentException("microphone");
        long first=Math.max(from,Math.max(0,samples-RECENT));int n=(int)Math.max(0,samples-first);short[] out=new short[n];
        for(int i=0;i<n;i++)out[i]=recent[index][(int)((first+i)%RECENT)];
        return out;
    }
    interface Listener {
        void utterance(Utterance value);
        default void frame(short[] interleaved) { }
    }
    void add(short[] input,int count,Listener listener) {
        for(int i=0;i<count;i++) { frame[fill++]=input[i];if(fill==frame.length){fill=0;process(listener);} }
    }
    private void process(Listener listener) {
        double[] rms=new double[6];
        for(int m=0;m<6;m++) {
            double sum=0,squares=0;for(int f=0;f<FRAME;f++){double v=frame[f*10+MICS[m]];sum+=v;squares+=v*v;recent[m][(int)((samples+f)%RECENT)]=(short)v;}
            rms[m]=Math.sqrt(Math.max(0,squares/FRAME-Math.pow(sum/FRAME,2)));
        }
        long frameStart=samples;samples+=FRAME;
        strongest=0;for(int m=1;m<6;m++)if(rms[m]>rms[strongest])strongest=m;
        level=rms[strongest];levels=rms.clone();
        // A quiet 1s baseline, then adapt only below the speech threshold.
        if(warming){noise=warmFrames==0?level:noise*0.9+level*0.1;if(++warmFrames>=50)warming=false;}
        boolean voice=!warming && level>Math.max(35,noise*2.5);
        if(!active && !voice)noise=noise*0.995+Math.min(level,noise*1.5)*0.005;
        if(voice && !active) {
            active=true;used=0;voiced=0;quiet=0;Arrays.fill(energy,0);startSample=frameStart-available;
            for(int i=available;i>0;i--){for(int m=0;m<6;m++)speech[m][used]=pre[m][(ring-i+PRE)%PRE];used++;}
        }
        listener.frame(frame);
        if(active) {
            for(int f=0;f<FRAME && used<MAX;f++){for(int m=0;m<6;m++)speech[m][used]=frame[f*10+MICS[m]];used++;}
            if(voice){voiced+=FRAME;quiet=0;for(int m=0;m<6;m++)energy[m]+=rms[m]*rms[m];}else quiet+=FRAME;
            if(quiet>=END_QUIET || used>=MAX) {
                int best=0;for(int m=1;m<6;m++)if(energy[m]>energy[best])best=m;
                if(voiced>=MIN_VOICED)listener.utterance(new Utterance(Arrays.copyOf(speech[best],used),energy.clone(),MICS[best],voiced,startSample,startSample+used));
                active=false;used=0;available=0;
            }
        }
        for(int f=0;f<FRAME;f++) { for(int m=0;m<6;m++)pre[m][ring]=frame[f*10+MICS[m]];ring=(ring+1)%PRE;available=Math.min(PRE,available+1); }
    }
}
