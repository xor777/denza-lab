package dev.denza.adaslink.stocknative;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class GenernalNavigationParam {
    private int intervalCameraSpeedDisttoEnd;
    private int intervalCameraSpeedDisttoStart;
    private int newNoODDRegionDist;

    public int getIntervalCameraSpeedDisttoEnd() {
        return this.intervalCameraSpeedDisttoEnd;
    }

    public int getIntervalCameraSpeedDisttoStart() {
        return this.intervalCameraSpeedDisttoStart;
    }

    public int getNewNoODDRegionDist() {
        return this.newNoODDRegionDist;
    }

    public void reSetGenernalNavigationParam() {
        this.intervalCameraSpeedDisttoStart = 0;
        this.intervalCameraSpeedDisttoEnd = 0;
        this.newNoODDRegionDist = 0;
    }

    public void setGenernalNavigationParam(int i2, int i3, int i4) {
        this.intervalCameraSpeedDisttoStart = i2;
        this.intervalCameraSpeedDisttoEnd = i3;
        this.newNoODDRegionDist = i4;
    }

    public void setIntervalCameraSpeedDisttoEnd(int i2) {
        this.intervalCameraSpeedDisttoEnd = i2;
    }

    public void setIntervalCameraSpeedDisttoStart(int i2) {
        this.intervalCameraSpeedDisttoStart = i2;
    }

    public void setNewNoODDRegionDist(int i2) {
        this.newNoODDRegionDist = i2;
    }
}
