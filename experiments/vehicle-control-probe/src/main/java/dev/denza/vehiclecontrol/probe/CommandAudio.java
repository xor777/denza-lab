package dev.denza.vehiclecontrol.probe;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded PCM handoff from the same 10ch recorder, no second microphone client.
 *
 * The command can start before the call is recognised: the words after the name in the same breath
 * and the audio recorded since the call ended are handed over first ({@link #prime}), live frames
 * follow without a gap because both are queued from the capture thread.
 */
final class CommandAudio {
    final long id;
    final int microphone;
    private final double gain;
    private final ArrayBlockingQueue<short[]> queue=new ArrayBlockingQueue<>(100);
    private final ConcurrentLinkedQueue<short[]> head=new ConcurrentLinkedQueue<>();
    volatile boolean overflow;
    CommandAudio(long id,int microphone,double gain){this.id=id;this.microphone=microphone;this.gain=gain;}
    private short scaled(int value){return (short)Math.max(-32768,Math.min(32767,Math.round(value*gain)));}
    void offerFrame(short[] interleaved){
        short[] mono=new short[interleaved.length/10];for(int i=0;i<mono.length;i++)mono[i]=scaled(interleaved[i*10+microphone]);
        if(!queue.offer(mono))overflow=true;
    }
    /** Mono audio of this microphone that precedes the live frames, in 0.2 s blocks. */
    void prime(short[] mono){
        for(int start=0;start<mono.length;start+=3200){
            int n=Math.min(3200,mono.length-start);short[] block=new short[n];
            for(int i=0;i<n;i++)block[i]=scaled(mono[start+i]);head.add(block);
        }
    }
    short[] poll()throws InterruptedException{short[] early=head.poll();return early!=null?early:queue.poll(100,TimeUnit.MILLISECONDS);}
}
