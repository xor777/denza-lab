package dev.denza.adaslink.stocknative;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class TunnelParam {
    private double endLat;
    private double endLon;
    private double startLat;
    private double startLon;
    private int tunnelDist;
    private int tunnelState;

    public void clearTunnelParam() {
        this.tunnelDist = 0;
        this.tunnelState = 0;
        this.startLon = 0.0d;
        this.startLat = 0.0d;
        this.endLon = 0.0d;
        this.endLat = 0.0d;
    }

    public double getEndLat() {
        return this.endLat;
    }

    public double getEndLon() {
        return this.endLon;
    }

    public double getStartLat() {
        return this.startLat;
    }

    public double getStartLon() {
        return this.startLon;
    }

    public int getTunnelDist() {
        return this.tunnelDist;
    }

    public int getTunnelState() {
        return this.tunnelState;
    }

    public void setEndLat(double d2) {
        this.endLat = d2;
    }

    public void setEndLon(double d2) {
        this.endLon = d2;
    }

    public void setStartLat(double d2) {
        this.startLat = d2;
    }

    public void setStartLon(double d2) {
        this.startLon = d2;
    }

    public void setTunnelDist(int i2) {
        this.tunnelDist = i2;
    }

    public void setTunnelParam(int i2, int i3, double d2, double d3, double d4, double d5) {
        this.tunnelDist = i2;
        this.tunnelState = i3;
        this.startLon = d2;
        this.startLat = d3;
        this.endLon = d4;
        this.endLat = d5;
    }

    public void setTunnelState(int i2) {
        this.tunnelState = i2;
    }
}
