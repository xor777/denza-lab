package dev.denza.adaslink.stocknative;

import java.io.Serializable;

public class LinkItem implements Serializable {
    private static final long serialVersionUID = -6245043502618245079L;

    @WireName("adminCode")
    public int adminCode;

    @WireName("assistantAction")
    public int assistantAction;

    @WireName("direction")
    public int direction;

    @WireName("formway")
    public int formway;

    @WireName("hasMixFork")
    public boolean hasMixFork;

    @WireName("hasMultiOut")
    public boolean hasMultiOut;

    @WireName("hasParallel")
    public boolean hasParallel;

    @WireName("hasTrafficLight")
    public boolean hasTrafficLight;

    @WireName("laneNum")
    public int laneNum;

    @WireName("len")
    public float len;

    @WireName("linkID")
    public long linkID;

    @WireName("linktype")
    public int linktype;

    @WireName("mainAction")
    public int mainAction;

    @WireName("ownership")
    public int ownership;

    @WireName("pntBegIdx")
    public int pntBegIdx;

    @WireName("pntCnt")
    public int pntCnt;

    @WireName("roadclass")
    public int roadclass;

    @WireName("roadname")
    public String roadname;

    @WireName("speedLimit")
    public int speedLimit;

    public LinkItem() {
    }

    public LinkItem(int i2, int i3, int i4, String str, float f2, int i5, int i6) {
        this.formway = i2;
        this.linktype = i3;
        this.roadclass = i4;
        this.roadname = str;
        this.len = f2;
        this.pntBegIdx = i5;
        this.pntCnt = i6;
    }
}
