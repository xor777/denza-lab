package dev.denza.adaslink.stocknative;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class SpeedIntersectionInformationParam {
    private int recordDist;
    private int trafficFlowSpeed = 0;
    private int historySpeed = 0;
    private int hasParallelRoad = 0;
    private int hasMixFork = 0;
    private int hasLongSolidLane = 0;
    private int distance = 0;
    private String naviVoiceCommand = "";
    private int finalmeter = 0;

    public int getDistance() {
        return this.distance;
    }

    public int getFinalmeter() {
        return this.finalmeter;
    }

    public int getHasLongSolidLane() {
        return this.hasLongSolidLane;
    }

    public int getHasMixFork() {
        return this.hasMixFork;
    }

    public int getHasParallelRoad() {
        return this.hasParallelRoad;
    }

    public int getHistorySpeed() {
        return this.historySpeed;
    }

    public String getNaviVoiceCommand() {
        return this.naviVoiceCommand;
    }

    public int getRecordDist() {
        return this.recordDist;
    }

    public int getTrafficFlowSpeed() {
        return this.trafficFlowSpeed;
    }

    public void reSetIntersectionInfoParam() {
        this.hasParallelRoad = 0;
        this.hasMixFork = 0;
        this.hasLongSolidLane = 0;
        this.distance = -1;
        this.naviVoiceCommand = "";
    }

    public void reSetSpeedIntersectionInformationParam() {
        this.trafficFlowSpeed = 0;
        this.historySpeed = 0;
        this.hasParallelRoad = 0;
        this.hasMixFork = 0;
        this.hasLongSolidLane = 0;
        this.distance = -1;
        this.naviVoiceCommand = "";
    }

    public void setDistance(int i2) {
        this.distance = i2;
    }

    public void setFinalmeter(int i2) {
        this.finalmeter = i2;
    }

    public void setHasLongSolidLane(int i2) {
        this.hasLongSolidLane = i2;
    }

    public void setHasMixFork(int i2) {
        this.hasMixFork = i2;
    }

    public int setHasParallelRoad(int i2) {
        this.hasParallelRoad = i2;
        return i2;
    }

    public void setHistorySpeed(int i2) {
        this.historySpeed = i2;
    }

    public void setNaviVoiceCommand(String str) {
        this.naviVoiceCommand = str;
    }

    public void setRecordDist(int i2) {
        this.recordDist = i2;
    }

    public void setTrafficFlowSpeed(int i2) {
        this.trafficFlowSpeed = i2;
    }
}
