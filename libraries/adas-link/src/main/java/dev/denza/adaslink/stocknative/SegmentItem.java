package dev.denza.adaslink.stocknative;

import java.io.Serializable;

public class SegmentItem implements Serializable {
    private static final long serialVersionUID = -7634331576891708639L;

    @WireName("crntSegmLinkCnt")
    public int crntSegmLinkCnt;

    @WireName("isArriveWayPoint")
    public boolean isArriveWayPoint;

    @WireName("linkBegIdx")
    public long linkBegIdx;

    @WireName("navigationAssitAction")
    public int navigationAssitAction;

    @WireName("navigationLen")
    public int navigationLen;

    @WireName("navigationMainAction")
    public int navigationMainAction;

    @WireName("trafficLightNum")
    public long trafficLightNum;

    @WireName("description")
    public String description = "";

    @WireName("navigationNextRoadName")
    public String navigationNextRoadName = "";
}
