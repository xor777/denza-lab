package dev.denza.adaslink.stocknative;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Objects;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class IntersectionLaneInfoParam implements Serializable {
    private transient StockRuntime clock;

    public IntersectionLaneInfoParam(StockRuntime clock) {
        this.clock = clock;
    }

    private static final long serialVersionUID = 3612270513855820294L;
    private final ArrayList<Integer> backLane = new ArrayList<>();
    private final ArrayList<Integer> frontLane = new ArrayList<>();
    private final ArrayList<Integer> extendLane = new ArrayList<>();
    private final ArrayList<Integer> recommendLane = new ArrayList<>();
    private final ArrayList<Integer> backLaneType = new ArrayList<>();
    private final ArrayList<Integer> frontLaneType = new ArrayList<>();
    private final ArrayList<Integer> backExtenLane = new ArrayList<>();
    private int segmentIndex = -1;
    private int linkIndex = -1;
    private long timestamp = -1;
    private int laneDist = 0;
    private int tidalLane = 0;

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        IntersectionLaneInfoParam intersectionLaneInfoParam = (IntersectionLaneInfoParam) obj;
        return this.segmentIndex == intersectionLaneInfoParam.segmentIndex
                && this.linkIndex == intersectionLaneInfoParam.linkIndex
                && this.timestamp == intersectionLaneInfoParam.timestamp
                && Objects.equals(this.backLane, intersectionLaneInfoParam.backLane)
                && Objects.equals(this.frontLane, intersectionLaneInfoParam.frontLane)
                && Objects.equals(this.extendLane, intersectionLaneInfoParam.extendLane)
                && Objects.equals(this.recommendLane, intersectionLaneInfoParam.recommendLane)
                && Objects.equals(this.backLaneType, intersectionLaneInfoParam.backLaneType)
                && Objects.equals(this.frontLaneType, intersectionLaneInfoParam.frontLaneType)
                && Objects.equals(this.backExtenLane, intersectionLaneInfoParam.backExtenLane);
    }

    public ArrayList<Integer> getBackExtenLane() {
        return this.backExtenLane;
    }

    public ArrayList<Integer> getBackLane() {
        return this.backLane;
    }

    public ArrayList<Integer> getBackLaneType() {
        return this.backLaneType;
    }

    public ArrayList<Integer> getExtendLane() {
        return this.extendLane;
    }

    public ArrayList<Integer> getFrontLane() {
        return this.frontLane;
    }

    public ArrayList<Integer> getFrontLaneType() {
        return this.frontLaneType;
    }

    public int getLaneDist() {
        return this.laneDist;
    }

    public int getLinkIndex() {
        return this.linkIndex;
    }

    public ArrayList<Integer> getRecommendLane() {
        return this.recommendLane;
    }

    public int getSegmentIndex() {
        return this.segmentIndex;
    }

    public int getTidalLane() {
        return this.tidalLane;
    }

    public long getTimestamp() {
        if (this.timestamp == -1) {
            this.timestamp = clock.currentTimeMillis();
        }
        return this.timestamp;
    }

    public int hashCode() {
        return Objects.hash(this.backLane, this.frontLane, this.extendLane, this.recommendLane, this.backLaneType, this.frontLaneType, Integer.valueOf(this.segmentIndex), Integer.valueOf(this.linkIndex), Long.valueOf(this.timestamp), this.backExtenLane);
    }

    public void reSetNextIntersectionLaneInfoParam() {
        this.backLane.clear();
        this.frontLane.clear();
        this.extendLane.clear();
        this.recommendLane.clear();
        this.backLaneType.clear();
        this.frontLaneType.clear();
        this.backExtenLane.clear();
        this.segmentIndex = -1;
        this.linkIndex = -1;
        this.timestamp = -1L;
        this.tidalLane = 1;
        this.laneDist = 0;
    }

    public void setLaneDist(int i2) {
        this.laneDist = i2;
    }

    public void setLaneParam(
            ArrayList<Integer> arrayList,
            ArrayList<Integer> arrayList2,
            ArrayList<Integer> arrayList3,
            ArrayList<Integer> arrayList4,
            ArrayList<Integer> arrayList5,
            ArrayList<Integer> arrayList6,
            int i2,
            int i3,
            long j2,
            ArrayList<Integer> arrayList7) {
        this.backLane.clear();
        this.backLane.addAll(arrayList);
        this.frontLane.clear();
        this.frontLane.addAll(arrayList2);
        this.extendLane.clear();
        this.extendLane.addAll(arrayList3);
        this.recommendLane.clear();
        this.recommendLane.addAll(arrayList4);
        this.backLaneType.clear();
        this.backLaneType.addAll(arrayList5);
        this.frontLaneType.clear();
        this.frontLaneType.addAll(arrayList6);
        this.segmentIndex = i2;
        this.linkIndex = i3;
        this.timestamp = j2;
        this.tidalLane = arrayList.contains(25) ? 2 : 1;
        this.backExtenLane.clear();
        this.backExtenLane.addAll(arrayList7);
    }

    public void setLinkIndex(int i2) {
        this.linkIndex = i2;
    }

    public void setSegmentIndex(int i2) {
        this.segmentIndex = i2;
    }

    public void setTidalLane(int i2) {
        this.tidalLane = i2;
    }

    public void setTimestamp(long j2) {
        this.timestamp = j2;
    }

    public String toString() {
        return "{segIdx=" + this.segmentIndex + ", linkIdx=" + this.linkIndex + ", backLane=" + this.backLane + ", frontLane=" + this.frontLane + ", extendLane=" + this.extendLane + ", recommendLane=" + this.recommendLane + ", backLaneType=" + this.backLaneType + ", frontLaneType=" + this.frontLaneType + ", timestamp=" + this.timestamp + ", tidalLane=" + this.tidalLane + ", laneDist=" + this.laneDist + ", backExtLane=" + this.backExtenLane + '}';
    }
}
