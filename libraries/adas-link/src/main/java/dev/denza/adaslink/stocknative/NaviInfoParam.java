package dev.denza.adaslink.stocknative;

import java.util.ArrayList;
import java.util.List;

/** Mutable sender state. Six selected accessors across this bean and its paired bean
 * have optional host-only observation; field logic remains original. */
public class NaviInfoParam {
    private final List<Integer> tollGateLaneTypes = new ArrayList();
    private int curSegDist = -1;
    private long pathId = -1;
    private int curSegIdx = -1;
    private int curLinkIdx = -1;
    private int linkRemainDist = -1;
    private int ownership = -1;

    public int getCurLinkIdx() {
        StockRuntime observer=NativeStateTrace.enter(this,"com.byd.map.signal.someip.pilot.bean.NaviInfoParam.getCurLinkIdx");
        int result=this.curLinkIdx;
        NativeStateTrace.exit(observer,this,"com.byd.map.signal.someip.pilot.bean.NaviInfoParam.getCurLinkIdx",result);
        return result;
    }

    public int getCurSegDist() {
        return this.curSegDist;
    }

    public int getCurSegIdx() {
        StockRuntime observer=NativeStateTrace.enter(this,"com.byd.map.signal.someip.pilot.bean.NaviInfoParam.getCurSegIdx");
        int result=this.curSegIdx;
        NativeStateTrace.exit(observer,this,"com.byd.map.signal.someip.pilot.bean.NaviInfoParam.getCurSegIdx",result);
        return result;
    }

    public int getLinkRemainDist() {
        return this.linkRemainDist;
    }

    public int getOwnership() {
        return this.ownership;
    }

    public long getPathId() {
        return this.pathId;
    }

    public List<Integer> getTollGateLaneTypes() {
        return this.tollGateLaneTypes;
    }

    public void reSetNaviInfo() {
        StockRuntime observer=NativeStateTrace.enter(this,"com.byd.map.signal.someip.pilot.bean.NaviInfoParam.reSetNaviInfo");
        this.curSegDist = -1;
        this.pathId = -1L;
        this.curSegIdx = -1;
        this.curLinkIdx = -1;
        this.linkRemainDist = -1;
        this.tollGateLaneTypes.clear();
        this.ownership = -1;
        NativeStateTrace.exit(observer,this,"com.byd.map.signal.someip.pilot.bean.NaviInfoParam.reSetNaviInfo",null);
    }

    public void setNaviInfoParam(int i2, long j2, int i3, int i4, int i5) {
        StockRuntime observer=NativeStateTrace.enter(this,"com.byd.map.signal.someip.pilot.bean.NaviInfoParam.setNaviInfoParam" ,i2,j2,i3,i4,i5);
        this.curSegDist = i2;
        this.pathId = j2;
        this.curSegIdx = i3;
        this.curLinkIdx = i4;
        this.linkRemainDist = i5;
        NativeStateTrace.exit(observer,this,"com.byd.map.signal.someip.pilot.bean.NaviInfoParam.setNaviInfoParam",null);
    }

    public void setOwnership(int i2) {
        this.ownership = i2;
    }
}
