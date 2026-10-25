package dev.denza.adaslink.stocknative;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class TrafficEventBean {
    private int laneId;
    private double lat;
    private double lon;
    private int type;

    public TrafficEventBean() {
    }

    public TrafficEventBean(int i2, int i3, double d2, double d3) {
        this.type = i2;
        this.laneId = i3;
        this.lon = d2;
        this.lat = d3;
    }

    public int getLaneId() {
        return this.laneId;
    }

    public double getLat() {
        return this.lat;
    }

    public double getLon() {
        return this.lon;
    }

    public int getType() {
        return this.type;
    }

    public void setLaneId(int i2) {
        this.laneId = i2;
    }

    public void setLat(double d2) {
        this.lat = d2;
    }

    public void setLon(double d2) {
        this.lon = d2;
    }

    public void setType(int i2) {
        this.type = i2;
    }

    public String toString() {
        return "TrafficEventBean{type=" + this.type + ", laneId=" + this.laneId + ", lon=" + this.lon + ", lat=" + this.lat + '}';
    }
}
