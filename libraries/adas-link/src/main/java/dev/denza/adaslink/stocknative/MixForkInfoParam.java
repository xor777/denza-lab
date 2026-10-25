package dev.denza.adaslink.stocknative;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class MixForkInfoParam {
    private long distance;
    private double lat;
    private double lon;

    private int roadclass;
    private int segmentIndex;

    public MixForkInfoParam() {
    }

    public MixForkInfoParam(long j2, double d2, double d3, int i2, int i3) {
        this.distance = j2;
        this.lon = d2;
        this.lat = d3;
        this.roadclass = i2;
        this.segmentIndex = i3;
    }

    public long getDistance() {
        return this.distance;
    }

    public double getLat() {
        return this.lat;
    }

    public double getLon() {
        return this.lon;
    }

    public int getRoadclass() {
        return this.roadclass;
    }

    public int getSegmentIndex() {
        return this.segmentIndex;
    }

    public void setDistance(long j2) {
        this.distance = j2;
    }

    public void setLat(double d2) {
        this.lat = d2;
    }

    public void setLon(double d2) {
        this.lon = d2;
    }

    public void setRoadclass(int i2) {
        this.roadclass = i2;
    }

    public void setSegmentIndex(int i2) {
        this.segmentIndex = i2;
    }
}
