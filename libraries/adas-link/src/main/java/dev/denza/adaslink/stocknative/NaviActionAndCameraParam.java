package dev.denza.adaslink.stocknative;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class NaviActionAndCameraParam {
    private int cameraType;
    private int iconType;
    private int mainAction = 0;
    private int assistantAction = 0;
    private int naviDistance = -1;
    private int cameraDistance = -1;

    public int getAssistantAction() {
        return this.assistantAction;
    }

    public int getCameraDistance() {
        return this.cameraDistance;
    }

    public int getCameraType() {
        return this.cameraType;
    }

    public int getIconType() {
        return this.iconType;
    }

    public int getMainAction() {
        return this.mainAction;
    }

    public int getNaviDistance() {
        return this.naviDistance;
    }

    public void reSetNaviActionAndCameraParam() {
        this.iconType = 0;
        this.mainAction = 0;
        this.assistantAction = 0;
        this.naviDistance = -1;
        this.cameraType = 0;
        this.cameraDistance = -1;
    }

    public void setAssistantAction(int i2) {
        this.assistantAction = i2;
    }

    public void setCameraDistance(int i2) {
        this.cameraDistance = i2;
    }

    public void setCameraType(int i2) {
        this.cameraType = i2;
    }

    public void setIconType(int i2) {
        this.iconType = i2;
    }

    public void setMainAction(int i2) {
        this.mainAction = i2;
    }

    public void setNaviActionParam(int i2, int i3, int i4, int i5) {
        this.iconType = i2;
        this.mainAction = i3;
        this.assistantAction = i4;
        this.naviDistance = i5;
    }

    public void setNaviDistance(int i2) {
        this.naviDistance = i2;
    }
}
