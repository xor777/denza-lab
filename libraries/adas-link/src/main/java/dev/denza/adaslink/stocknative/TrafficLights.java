package dev.denza.adaslink.stocknative;

import java.io.Serializable;

public class TrafficLights implements Serializable {
    private static final long serialVersionUID = 3965421061052071308L;

    @WireName("x")
    public double f2965x;

    @WireName("y")
    public double f2966y;

    public TrafficLights(double d2, double d3) {
        this.f2965x = d2;
        this.f2966y = d3;
    }
}
