package dev.denza.adaslink.stocknative;

import java.io.Serializable;
import java.util.ArrayList;

public class GuideGroup implements Serializable {
    private static final long serialVersionUID = -1350471287218688165L;

    @WireName("groupIconType")
    public int groupIconType;

    @WireName("groupLen")
    public int groupLen;

    @WireName("groupName")
    public String groupName;

    @WireName("groupTime")
    public int groupTime;

    @WireName("groupTrafficLightsCount")
    public int groupTrafficLightsCount;

    @WireName("groupEnterCoord")
    public GroupEnterCoord mGroupEnterCoord;

    @WireName("segments")
    public ArrayList<SegmentItem> segments = new ArrayList<>();
}
