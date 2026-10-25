package dev.denza.adaslink.stocknative;

import java.io.Serializable;

public class ViaRoadInfoItemPos implements Serializable {
    private static final long serialVersionUID = -1350471287318688165L;

    @WireName("lat")
    public double lat;

    @WireName("lon")
    public double lon;

    public ViaRoadInfoItemPos(double d2, double d3) {
        this.lon = d2;
        this.lat = d3;
    }
}
