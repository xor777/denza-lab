package dev.denza.vehiclecontrol.probe;

import java.util.Arrays;

/** 10ch interleaved PCM -> bounded utterances. No channel is labelled as a seat. */
final class CabinSpeech {
    static final int[] MICS={0,1,4,5,8,9};
    static final int FRAME=320, PRE=3200, MAX=80000;
    record Utterance(short[] pcm,double[] energy,int microphone,int voicedSamples) { }
    private final short[][] pre=new short[6][PRE];
    private final short[] frame=new short[FRAME*10],speech=new short[MAX];
    private final double[] energy=new double[6];
    private int fill,ring,available,used,quiet,active=-1,voiced;
    private double noise=12;
    private boolean warming=true;
    private int warmFrames;
    double level;
    double[] levels=new double[6];
    boolean speaking(){return active>=0;}
    int strongest;
    static short[] normalize(short[] input) {
        int peak=0;for(short value:input)peak=Math.max(peak,Math.abs((int)value));
        double gain=peak==0?1:Math.min(8,12000.0/peak);
        short[] out=new short[input.length];for(int i=0;i<out.length;i++)out[i]=(short)Math.round(input[i]*gain);return out;
    }
    void resetUtterance(){active=-1;used=0;quiet=0;available=0;voiced=0;}
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
            double sum=0,squares=0;for(int f=0;f<FRAME;f++){double v=frame[f*10+MICS[m]];sum+=v;squares+=v*v;}
            rms[m]=Math.sqrt(Math.max(0,squares/FRAME-Math.pow(sum/FRAME,2)));
        }
        strongest=0;for(int m=1;m<6;m++)if(rms[m]>rms[strongest])strongest=m;
        level=rms[strongest];levels=rms.clone();
        // A quiet 1s baseline, then adapt only below the speech threshold.
        if(warming){noise=warmFrames==0?level:noise*0.9+level*0.1;if(++warmFrames>=50)warming=false;}
        boolean voice=!warming && level>Math.max(35,noise*2.5);
        if(active<0 && !voice)noise=noise*0.995+Math.min(level,noise*1.5)*0.005;
        if(voice && active<0) {
            active=strongest;used=0;voiced=0;quiet=0;Arrays.fill(energy,0);
            for(int i=available;i>0;i--)speech[used++]=pre[active][(ring-i+PRE)%PRE];
        }
        listener.frame(frame);
        if(active>=0) {
            for(int f=0;f<FRAME && used<MAX;f++)speech[used++]=frame[f*10+MICS[active]];
            if(voice){voiced+=FRAME;quiet=0;for(int m=0;m<6;m++)energy[m]+=rms[m]*rms[m];}else quiet+=FRAME;
            if(quiet>=11200 || used>=MAX) {
                if(voiced>=4800)listener.utterance(new Utterance(Arrays.copyOf(speech,used),energy.clone(),MICS[active],voiced));
                active=-1;used=0;available=0;
            }
        }
        for(int f=0;f<FRAME;f++) { for(int m=0;m<6;m++)pre[m][ring]=frame[f*10+MICS[m]];ring=(ring+1)%PRE;available=Math.min(PRE,available+1); }
    }
}
