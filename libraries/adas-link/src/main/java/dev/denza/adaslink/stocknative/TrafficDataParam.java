package dev.denza.adaslink.stocknative;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class TrafficDataParam {
    private int distToDsttn;
    private double intervalCameraEndLat;
    private double intervalCameraEndLon;
    private int intervalCameraSpeedDisttoEnd;
    private int intervalCameraSpeedDisttoStart;
    private int intervalCameraSpeedValue;
    private double intervalCameraStartLat;
    private double intervalCameraStartLon;
    private boolean offCourse;
    private int timeToDsttn;
    private int trafficJamLength = 0;
    private int distToStartTrafficJam = -1;
    private int trafficJamStatus = 0;
    private int passTime = 0;
    private int cnstrctnRmnd = 0;
    private double cnstrctnCrdLon = 0.0d;
    private double cnstrctnCrdLat = 0.0d;
    private long cnstrcStartLink = 0;
    private int spdLmtSpeedValue = 0;
    private int spdLmtEleEyeSpeedValue = 0;
    private int spdLmtEleEyeDist = 0;
    private int roadClass = -1;
    private long linkId = -1;

    public long getCnstrcStartLink() {
        return this.cnstrcStartLink;
    }

    public double getCnstrctnCrdLat() {
        return this.cnstrctnCrdLat;
    }

    public double getCnstrctnCrdLon() {
        return this.cnstrctnCrdLon;
    }

    public int getCnstrctnRmnd() {
        return this.cnstrctnRmnd;
    }

    public int getDistToDsttn() {
        return this.distToDsttn;
    }

    public int getDistToStartTrafficJam() {
        return this.distToStartTrafficJam;
    }

    public double getIntervalCameraEndLat() {
        return this.intervalCameraEndLat;
    }

    public double getIntervalCameraEndLon() {
        return this.intervalCameraEndLon;
    }

    public int getIntervalCameraSpeedDisttoEnd() {
        return this.intervalCameraSpeedDisttoEnd;
    }

    public int getIntervalCameraSpeedDisttoStart() {
        return this.intervalCameraSpeedDisttoStart;
    }

    public int getIntervalCameraSpeedValue() {
        return this.intervalCameraSpeedValue;
    }

    public double getIntervalCameraStartLat() {
        return this.intervalCameraStartLat;
    }

    public double getIntervalCameraStartLon() {
        return this.intervalCameraStartLon;
    }

    public long getLinkId() {
        return this.linkId;
    }

    public boolean getOffCourse() {
        return this.offCourse;
    }

    public int getPassTime() {
        return this.passTime;
    }

    public int getRoadClass() {
        return this.roadClass;
    }

    public int getSpdLmtEleEyeDist() {
        return this.spdLmtEleEyeDist;
    }

    public int getSpdLmtEleEyeSpeedValue() {
        return this.spdLmtEleEyeSpeedValue;
    }

    public int getSpdLmtSpeedValue() {
        return this.spdLmtSpeedValue;
    }

    public int getTimeToDsttn() {
        return this.timeToDsttn;
    }

    public int getTrafficJamLength() {
        return this.trafficJamLength;
    }

    public int getTrafficJamStatus() {
        return this.trafficJamStatus;
    }

    public void intervalCameraStartLonAndLat() {
        this.intervalCameraStartLon = 0.0d;
        this.intervalCameraStartLat = 0.0d;
    }

    public void reSetCameraSpeedParam() {
        this.spdLmtEleEyeSpeedValue = 0;
        this.spdLmtEleEyeDist = 0;
    }

    public void reSetConstruction() {
        this.cnstrctnRmnd = 0;
        this.cnstrctnCrdLon = 0.0d;
        this.cnstrctnCrdLat = 0.0d;
        this.cnstrcStartLink = 0L;
    }

    public void reSetDestTimeAndDist() {
        this.distToDsttn = 0;
        this.timeToDsttn = 0;
    }

    public void reSetIntervalCameraSpeed() {
        this.intervalCameraSpeedValue = 0;
        this.intervalCameraSpeedDisttoStart = 0;
        this.intervalCameraSpeedDisttoEnd = 0;
        this.intervalCameraStartLon = 0.0d;
        this.intervalCameraStartLat = 0.0d;
        this.intervalCameraEndLon = 0.0d;
        this.intervalCameraEndLat = 0.0d;
    }

    public void reSetTrafficJam() {
        this.trafficJamLength = 0;
        this.distToStartTrafficJam = -1;
        this.trafficJamStatus = 0;
        this.passTime = 0;
    }

    public void reSetTrafficParam() {
        this.distToDsttn = 0;
        this.timeToDsttn = 0;
        this.spdLmtEleEyeSpeedValue = 0;
        this.spdLmtSpeedValue = 0;
        this.spdLmtEleEyeDist = 0;
        this.intervalCameraSpeedValue = 0;
        this.intervalCameraSpeedDisttoStart = 0;
        this.intervalCameraSpeedDisttoEnd = 0;
        this.intervalCameraStartLon = 0.0d;
        this.intervalCameraStartLat = 0.0d;
        this.intervalCameraEndLon = 0.0d;
        this.intervalCameraEndLat = 0.0d;
        this.cnstrctnRmnd = 0;
        this.cnstrctnCrdLon = 0.0d;
        this.cnstrctnCrdLat = 0.0d;
        this.cnstrcStartLink = 0L;
        this.trafficJamLength = 0;
        this.distToStartTrafficJam = -1;
        this.trafficJamStatus = 0;
        this.passTime = 0;
        this.offCourse = false;
    }

    public void setCameraSpeedParam(int i2, int i3) {
        this.spdLmtEleEyeSpeedValue = i2;
        this.spdLmtEleEyeDist = i3;
    }

    public void setConstruction(int i2, long j2, double d2, double d3) {
        this.cnstrctnRmnd = i2;
        this.cnstrcStartLink = j2;
        this.cnstrctnCrdLon = d2;
        this.cnstrctnCrdLat = d3;
    }

    public void setCurrentRoadSpeed(int i2) {
        this.spdLmtSpeedValue = i2;
    }

    public void setDestTimeAndDist(int i2, int i3) {
        this.distToDsttn = i2;
        this.timeToDsttn = i3;
    }

    public void setIntervalCameraSpeed(int i2, int i3, int i4) {
        this.intervalCameraSpeedValue = i2;
        this.intervalCameraSpeedDisttoStart = i3;
        this.intervalCameraSpeedDisttoEnd = i4;
    }

    public void setIntervalCameraSpeedParam(int i2, int i3, double d2, double d3, double d4, double d5, int i4) {
        this.spdLmtEleEyeSpeedValue = i2;
        this.spdLmtEleEyeDist = i3;
        this.intervalCameraStartLon = d2;
        this.intervalCameraStartLat = d3;
        this.intervalCameraEndLon = d4;
        this.intervalCameraEndLat = d5;
        this.intervalCameraSpeedValue = i4;
    }

    public void setLinkId(long j2) {
        this.linkId = j2;
    }

    public void setOffCourse(boolean z2) {
        this.offCourse = z2;
    }

    public void setRoadClass(int i2) {
        this.roadClass = i2;
    }

    public void setTrafficJam(int i2, int i3, int i4, int i5) {
        this.trafficJamLength = i2;
        this.distToStartTrafficJam = i3;
        this.trafficJamStatus = i4;
        this.passTime = i5;
    }
}
