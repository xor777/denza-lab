package dev.denza.adaslink.stocknative;

import java.io.Serializable;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class TrafficLightState implements Serializable {
    private static final long serialVersionUID = -3265427690993484293L;
    public long etime;
    public long stime;
    public int type;

    public TrafficLightState() {
        this.type = 0;
        this.stime = 0L;
        this.etime = 0L;
    }

    public TrafficLightState(int i2, long j2, long j3) {
        this.type = i2;
        this.stime = j2;
        this.etime = j3;
    }
}
