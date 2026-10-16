package dev.denza.vehiclecontrol.probe;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Bounded PCM handoff from the same 10ch recorder, no second microphone client. */
final class CommandAudio {
    final long id;
    private final int microphone;
    private final double gain;
    private final ArrayBlockingQueue<short[]> queue=new ArrayBlockingQueue<>(100);
    volatile boolean overflow;
    CommandAudio(long id,int microphone,double gain){this.id=id;this.microphone=microphone;this.gain=gain;}
    void offerFrame(short[] interleaved){
        short[] mono=new short[interleaved.length/10];for(int i=0;i<mono.length;i++)mono[i]=(short)Math.max(-32768,Math.min(32767,Math.round(interleaved[i*10+microphone]*gain)));
        if(!queue.offer(mono))overflow=true;
    }
    short[] poll()throws InterruptedException{return queue.poll(100,TimeUnit.MILLISECONDS);}
}
