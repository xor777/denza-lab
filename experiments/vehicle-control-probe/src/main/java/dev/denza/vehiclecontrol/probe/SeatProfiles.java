package dev.denza.vehiclecontrol.probe;
import org.json.*;
import java.util.List;

/** Calibrated six-mic level templates. Physical channel order is not assumed. */
final class SeatProfiles {
    static final String[] LABELS={"Водитель","Передний пассажир","2-й ряд слева","2-й ряд справа","3-й ряд слева","3-й ряд справа"};
    private final double[][] profiles=new double[6][];
    private final int[] samples=new int[6];
    SeatProfiles(JSONObject saved){if(saved!=null)for(int i=0;i<6;i++){JSONArray a=saved.optJSONArray("seat_"+i);if(a==null||a.length()!=6)continue;double[] v=new double[6];for(int j=0;j<6;j++)v[j]=a.optDouble(j,-1);profiles[i]=unit(v);if(profiles[i]!=null)samples[i]=Math.max(1,saved.optInt("count_"+i,1));}}
    synchronized int count(){int n=0;for(int i=0;i<6;i++)if(samples[i]>=3)n++;return n;}
    synchronized boolean has(int seat){return profiles[seat]!=null;}
    synchronized int samples(int seat){return samples[seat];}
    synchronized void replace(int seat,List<double[]> energies){
        if(seat<0||seat>=6||energies.size()!=3)throw new IllegalArgumentException("Three samples required");
        double[] sum=new double[6];for(double[] e:energies){double[] p=normalize(e);if(p==null)throw new IllegalArgumentException("No signal");for(int i=0;i<6;i++)sum[i]+=p[i];}
        profiles[seat]=unit(sum);samples[seat]=3;
    }
    record Guess(int seat,double score,double margin,int calibrated){String label(){return seat<0?"Место не определено":"Предположительно: "+LABELS[seat];}}
    synchronized Guess guess(double[] energy){
        double[] p=normalize(energy);int best=-1;double score=-1,second=-1;
        if(p!=null)for(int s=0;s<6;s++)if(profiles[s]!=null){double x=0;for(int i=0;i<6;i++)x+=p[i]*profiles[s][i];if(x>score){second=score;score=x;best=s;}else second=Math.max(second,x);}
        return new Guess(count()==6&&score>=0.94&&score-second>=0.015?best:-1,score,score-second,count());
    }
    synchronized JSONObject json()throws Exception {JSONObject o=new JSONObject();for(int i=0;i<6;i++)if(profiles[i]!=null)o.put("seat_"+i,new JSONArray(profiles[i])).put("count_"+i,samples[i]);return o;}
    private static double[] unit(double[] p){if(p==null||p.length!=6)return null;double sum=0;for(double x:p){if(!Double.isFinite(x)||x<0)return null;sum+=x*x;}if(sum<=0)return null;double[] v=p.clone();for(int i=0;i<v.length;i++)v[i]/=Math.sqrt(sum);return v;}
    private static double[] normalize(double[] e){if(e==null||e.length!=6)return null;double[] p=new double[6];for(int i=0;i<6;i++){if(!Double.isFinite(e[i])||e[i]<0)return null;p[i]=Math.sqrt(e[i]);}return unit(p);}
}
