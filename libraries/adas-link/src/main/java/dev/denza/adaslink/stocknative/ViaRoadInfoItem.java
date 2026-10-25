package dev.denza.adaslink.stocknative;

import java.io.Serializable;

public class ViaRoadInfoItem implements Serializable {
    private static final long serialVersionUID = 8067957942187634521L;

    @WireName("length")
    public int length;

    @WireName("maxLaneNum")
    public int maxLaneNum;

    @WireName("maxLimitSpeed")
    public int maxLimitSpeed;

    @WireName("minLaneNum")
    public int minLaneNum;

    @WireName("minLimitSpeed")
    public int minLimitSpeed;

    @WireName("pos")
    public ViaRoadInfoItemPos pos;

    @WireName("roadClass")
    public int roadClass;

    @WireName("roadName")
    public String roadName;

    public ViaRoadInfoItem(String str, int i2, int i3, int i4, int i5, int i6, int i7, ViaRoadInfoItemPos viaRoadInfoItemPos) {
        this.roadName = str;
        this.maxLimitSpeed = i2;
        this.minLimitSpeed = i3;
        this.minLaneNum = i4;
        this.maxLaneNum = i5;
        this.length = i6;
        this.roadClass = i7;
        this.pos = viaRoadInfoItemPos;
    }
}
