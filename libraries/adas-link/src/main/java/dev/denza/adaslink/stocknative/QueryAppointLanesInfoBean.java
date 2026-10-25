package dev.denza.adaslink.stocknative;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Objects;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class QueryAppointLanesInfoBean implements Serializable {
    private transient StockRuntime clock;

    public QueryAppointLanesInfoBean(StockRuntime clock) {
        this.clock = clock;
    }

    private static final long serialVersionUID = 3612270513855820294L;
    private int linkIndex;
    private long pathId;
    private transient long requestId;
    private int segmentIndex;
    private long timestamp;
    private final ArrayList<Integer> backLane = new ArrayList<>();
    private final ArrayList<Integer> frontLane = new ArrayList<>();
    private final ArrayList<Integer> extendLane = new ArrayList<>();
    private final ArrayList<Integer> recommendLane = new ArrayList<>();
    private final ArrayList<Integer> backLaneType = new ArrayList<>();
    private final ArrayList<Integer> frontLaneType = new ArrayList<>();

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        QueryAppointLanesInfoBean queryAppointLanesInfoBean = (QueryAppointLanesInfoBean) obj;
        return this.segmentIndex == queryAppointLanesInfoBean.segmentIndex
                && this.linkIndex == queryAppointLanesInfoBean.linkIndex
                && this.timestamp == queryAppointLanesInfoBean.timestamp
                && this.pathId == queryAppointLanesInfoBean.pathId
                && Objects.equals(this.backLane, queryAppointLanesInfoBean.backLane)
                && Objects.equals(this.frontLane, queryAppointLanesInfoBean.frontLane)
                && Objects.equals(this.extendLane, queryAppointLanesInfoBean.extendLane)
                && Objects.equals(this.recommendLane, queryAppointLanesInfoBean.recommendLane)
                && Objects.equals(this.backLaneType, queryAppointLanesInfoBean.backLaneType)
                && Objects.equals(this.frontLaneType, queryAppointLanesInfoBean.frontLaneType);
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

    public int getLinkIndex() {
        return this.linkIndex;
    }

    public long getPathId() {
        return this.pathId;
    }

    public ArrayList<Integer> getRecommendLane() {
        return this.recommendLane;
    }

    public long getRequestId() {
        return this.requestId;
    }

    public int getSegmentIndex() {
        return this.segmentIndex;
    }

    public long getTimestamp() {
        long jCurrentTimeMillis = clock.currentTimeMillis();
        this.timestamp = jCurrentTimeMillis;
        return jCurrentTimeMillis;
    }

    public int hashCode() {
        return Objects.hash(this.backLane, this.frontLane, this.extendLane, this.recommendLane, this.backLaneType, this.frontLaneType, Integer.valueOf(this.segmentIndex), Integer.valueOf(this.linkIndex), Long.valueOf(this.timestamp), Long.valueOf(this.pathId));
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
            long j3) {
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
        this.requestId = j3;
    }

    public void setLinkIndex(int i2) {
        this.linkIndex = i2;
    }

    public void setPathId(long j2) {
        this.pathId = j2;
    }

    public void setRequestId(long j2) {
        this.requestId = j2;
    }

    public void setSegmentIndex(int i2) {
        this.segmentIndex = i2;
    }

    public void setTimestamp(long j2) {
        this.timestamp = j2;
    }

    public String toString() {
        return "{back=" + this.backLane + ", front=" + this.frontLane + ", extend=" + this.extendLane + ", recommend=" + this.recommendLane + ", backType=" + this.backLaneType + ", frontType=" + this.frontLaneType + ", segIdx=" + this.segmentIndex + ", linkIdx=" + this.linkIndex + ", timestamp=" + this.timestamp + ", pathId=" + this.pathId + '}';
    }
}
