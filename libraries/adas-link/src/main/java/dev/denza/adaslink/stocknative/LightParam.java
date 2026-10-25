package dev.denza.adaslink.stocknative;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class LightParam {
    private int direction;
    private int distance;
    private long endTime;
    private double lat;
    private int lightExist;
    private int lightState;
    private double lon;
    private long startTime;
    private int waitNum;

    public int getDirection() {
        return this.direction;
    }

    public int getDistance() {
        return this.distance;
    }

    public long getEndTime() {
        return this.endTime;
    }

    public double getLat() {
        return this.lat;
    }

    public int getLightExist() {
        return this.lightExist;
    }

    public int getLightState() {
        return this.lightState;
    }

    public double getLon() {
        return this.lon;
    }

    public long getStartTime() {
        return this.startTime;
    }

    public int getWaitNum() {
        return this.waitNum;
    }

    public void reSetLightLocationParam() {
        this.lightExist = 1;
        this.lon = 0.0d;
        this.lat = 0.0d;
        this.lightState = 0;
        this.startTime = 0L;
        this.endTime = 0L;
        this.direction = 0;
        this.waitNum = 0;
        this.distance = 0;
    }

    public void setLightLocationParam(int i2, double d2, double d3) {
        this.distance = i2;
        this.lon = d2;
        this.lat = d3;
        if (d2 == 0.0d && d3 == 0.0d) {
            reSetLightLocationParam();
        } else {
            if (d2 <= 0.0d || d3 <= 0.0d) {
                return;
            }
            this.lightExist = 2;
        }
    }

    public void setLightParam(int i2, double d2, double d3, int i3, long j2, long j3, int i4, int i5) {
        this.lightExist = i2;
        this.lon = d2;
        this.lat = d3;
        this.lightState = i3;
        this.startTime = j2;
        this.endTime = j3;
        this.direction = i4;
        this.waitNum = i5;
    }
}
