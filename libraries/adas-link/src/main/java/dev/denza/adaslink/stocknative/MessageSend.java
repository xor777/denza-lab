package dev.denza.adaslink.stocknative;

import java.io.Serializable;

public class MessageSend<T> implements Serializable {
    private static final long serialVersionUID = 5574732611982175165L;

    @WireName("code")
    public int code;

    @WireName("data")
    public T data;

    @WireName("endPOIInfo")
    public EndPoiInfo endPOIInfo;

    @WireName("mapProvider")
    public int mapProvider;

    @WireName("msg")
    public String msg;

    @WireName("RouteType")
    public int routeType;

    public MessageSend(String str, int i2, T t2) {
        this.mapProvider = 1;
        this.msg = str;
        this.code = i2;
        this.data = t2;
        this.mapProvider = 1;
    }

    public MessageSend(String str, int i2, T t2, EndPoiInfo endPoiInfo) {
        this.mapProvider = 1;
        this.msg = str;
        this.code = i2;
        this.data = t2;
        this.endPOIInfo = endPoiInfo;
    }
}
