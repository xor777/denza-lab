package dev.denza.adaslink.stocknative;

import java.io.Serializable;

public class PntItem implements Serializable {
    private static final long serialVersionUID = -8378939956371688378L;

    @WireName("x")
    public double f2963x;

    @WireName("y")
    public double f2964y;

    public PntItem(double d2, double d3) {
        this.f2963x = d2;
        this.f2964y = d3;
    }
}
