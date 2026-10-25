package dev.denza.adaslink.stocknative;

/** Mutable sender state. Six selected accessors across this bean and its paired bean
 * have optional host-only observation; field logic remains original. */
public class FontRoundBean {
    private int dstLinkDistance;
    private int toRampDist = -1;
    private int enterTunnelDist = -1;
    private int leaveTunnelDist = -1;
    private double[] tunnelEndLocation = new double[2];
    private int lightDist = -1;
    private double[] lightLocation = new double[2];
    private int[] font3kmIdx = new int[2];

    public int getDstLinkDistance() {
        return this.dstLinkDistance;
    }

    public int getEnterTunnelDist() {
        return this.enterTunnelDist;
    }

    public int[] getFont3kmIdx() {
        StockRuntime observer=NativeStateTrace.enter(this,"com.byd.map.signal.someip.pilot.bean.FontRoundBean.getFont3kmIdx");
        int[] result=this.font3kmIdx;
        NativeStateTrace.exit(observer,this,"com.byd.map.signal.someip.pilot.bean.FontRoundBean.getFont3kmIdx",result);
        return result;
    }

    public int getLeaveTunnelDist() {
        return this.leaveTunnelDist;
    }

    public int getLightDist() {
        return this.lightDist;
    }

    public double[] getLightLocation() {
        return this.lightLocation;
    }

    public int getToRampDist() {
        return this.toRampDist;
    }

    public double[] getTunnelEndLocation() {
        return this.tunnelEndLocation;
    }

    public void setParam(int i2, int i3, int i4, int i5, double[] dArr, int i6, double[] dArr2, int[] iArr) {
        StockRuntime observer=NativeStateTrace.enter(this,"com.byd.map.signal.someip.pilot.bean.FontRoundBean.setParam" ,i2,i3,i4,i5,dArr,i6,dArr2,iArr);
        this.lightDist = i6;
        this.lightLocation = dArr2;
        this.tunnelEndLocation = dArr;
        this.toRampDist = i3;
        this.dstLinkDistance = i2;
        this.leaveTunnelDist = i5;
        this.enterTunnelDist = i4;
        this.font3kmIdx = iArr;
        NativeStateTrace.exit(observer,this,"com.byd.map.signal.someip.pilot.bean.FontRoundBean.setParam",null);
    }
}
