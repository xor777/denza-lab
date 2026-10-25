package dev.denza.adaslink.stocknative;

import java.io.Serializable;
import java.util.List;

public class EndPoiInfo implements Serializable {
    private static final long serialVersionUID = 5574732611982175165L;

    @WireName("AVPMapIdList")
    public List<Integer> AVPMapIds;

    @WireName("PTPStateFlag")
    public int PTPStateFlag;

    public EndPoiInfo(List<Integer> list, int i2) {
        this.AVPMapIds = list;
        this.PTPStateFlag = i2;
    }
}
