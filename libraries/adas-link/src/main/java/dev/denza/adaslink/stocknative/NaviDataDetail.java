package dev.denza.adaslink.stocknative;

import java.io.Serializable;

public class NaviDataDetail implements Serializable {
    private static final long serialVersionUID = -3968940718641844458L;

    @WireName("currentIndex")
    public int currentIndex;

    @WireName("length")
    public int length;

    @WireName("pathCount")
    public int pathCount;

    @WireName("payload")
    public String payload;

    @WireName("time")
    public long time;

    public NaviDataDetail() {
    }

    public NaviDataDetail(long j2, int i2, int i3, int i4, String str) {
        this.time = j2;
        this.pathCount = i2;
        this.length = i3;
        this.currentIndex = i4;
        this.payload = str;
    }

    public int getCurrentIndex() {
        return this.currentIndex;
    }

    public int getLength() {
        return this.length;
    }

    public int getPathCount() {
        return this.pathCount;
    }

    public String getPayload() {
        return this.payload;
    }

    public long getTime() {
        return this.time;
    }

    public void setCurrentIndex(int i2) {
        this.currentIndex = i2;
    }

    public void setLength(int i2) {
        this.length = i2;
    }

    public void setPathCount(int i2) {
        this.pathCount = i2;
    }

    public void setPayload(String str) {
        this.payload = str;
    }

    public void setTime(long j2) {
        this.time = j2;
    }
}
