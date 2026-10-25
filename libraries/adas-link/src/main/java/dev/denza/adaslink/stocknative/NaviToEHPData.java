package dev.denza.adaslink.stocknative;

import java.io.Serializable;
import java.util.ArrayList;

public class NaviToEHPData implements Serializable {
    private static final long serialVersionUID = 3612270513811820294L;

    @WireName("guideGroups")
    public ArrayList<GuideGroup> guideGroups;

    @WireName("linkCnt")
    public int linkCnt;

    @WireName("links")
    public ArrayList<LinkItem> links;

    @WireName("pathID")
    public long pathID;

    @WireName("pntCnt")
    public int pntCnt;

    @WireName("pnts")
    public ArrayList<PntItem> pnts;

    @WireName("trafficLights")
    public ArrayList<TrafficLights> trafficLights;

    @WireName("viaRoadInfo")
    public ArrayList<ViaRoadInfoItem> viaRoadInfo;

    public NaviToEHPData() {
        this.pathID = 0L;
        this.linkCnt = 0;
        this.pntCnt = 0;
        this.links = new ArrayList<>();
        this.pnts = new ArrayList<>();
        this.trafficLights = new ArrayList<>();
        this.guideGroups = new ArrayList<>();
    }

    public NaviToEHPData(
            long j2,
            int i2,
            int i3,
            ArrayList<LinkItem> arrayList,
            ArrayList<PntItem> arrayList2,
            ArrayList<GuideGroup> arrayList3,
            ArrayList<TrafficLights> arrayList4,
            ArrayList<ViaRoadInfoItem> arrayList5) {
        this.pathID = j2;
        this.linkCnt = i2;
        this.pntCnt = i3;
        this.links = arrayList;
        this.pnts = arrayList2;
        this.guideGroups = arrayList3;
        this.trafficLights = arrayList4;
        this.viaRoadInfo = arrayList5;
    }

    public String toString() {
        return super.toString() + "pathid:" + this.pathID;
    }
}
