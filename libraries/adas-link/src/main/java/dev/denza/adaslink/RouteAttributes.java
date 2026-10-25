package dev.denza.adaslink;

import java.util.Map;

/** Immutable additional attributes on the exact selected route. Acquisition (files, SDK or
 * captured tiles) is outside the converter; missing fields remain individually unknown. */
public interface RouteAttributes {
    /** Typed renderer inputs only. Never an AutoSDK enum or a previously converted class. */
    final class RendererClass {
        public final Object rawClass, rawFc;
        public final String classType, fcType, quality;
        private RendererClass(Object rawClass,String classType,Object rawFc,String fcType,String quality){
            this.rawClass=scalar(rawClass);this.classType=classType;this.rawFc=scalar(rawFc);this.fcType=fcType;this.quality=quality==null?"source_unavailable":quality;
        }
        private static Object scalar(Object value){
            return value instanceof String||value instanceof Boolean||value instanceof Long||value instanceof Integer
                    ||value instanceof Float||value instanceof Double?value:null;
        }
        public static RendererClass literal(Object roadClass,String classType,Object fc,String fcType){
            return new RendererClass(roadClass,classType,fc,fcType,"sampled_typed_renderer_consensus");
        }
        public static RendererClass unknown(String reason){return new RendererClass(null,null,null,null,reason);}
        /** A driving class requires string class and a consistent integral protobuf fc. */
        public Integer functionalClass(){
            if(!"string".equals(classType)||!(rawClass instanceof String))return null;
            String name=(String)rawClass;if(name.endsWith("_link"))name=name.substring(0,name.length()-5);
            if(name.length()!=1||name.charAt(0)<'1'||name.charAt(0)>'9')return null;
            int category=name.charAt(0)-'0';long fc;
            if(("uint32".equals(fcType)||"sint32".equals(fcType))&&(rawFc instanceof Long||rawFc instanceof Integer))fc=((Number)rawFc).longValue();
            else if("uint64".equals(fcType)&&rawFc instanceof String){try{fc=Long.parseLong((String)rawFc);}catch(NumberFormatException bad){return null;}}
            else return null;
            return fc==category?category:null;
        }
        @Override public boolean equals(Object value){
            if(!(value instanceof RendererClass))return false;RendererClass r=(RendererClass)value;
            return java.util.Objects.equals(rawClass,r.rawClass)&&java.util.Objects.equals(rawFc,r.rawFc)
                    &&java.util.Objects.equals(classType,r.classType)&&java.util.Objects.equals(fcType,r.fcType)&&java.util.Objects.equals(quality,r.quality);
        }
        @Override public int hashCode(){return java.util.Objects.hash(rawClass,classType,rawFc,fcType,quality);}
        public static RendererClass consensus(RendererClass a,RendererClass b){return a.equals(b)?a:unknown("raw_class_or_type_conflict");}
    }
    boolean matches(YandexRoute route);
    /** Scalar boundaries at attribute changes, in segmentIndex + fraction coordinates. */
    double[] cuts();
    /** Only fields supported throughout this interval; never a midpoint-only assertion. */
    RoadNetwork.KnownAttributes attributesBetween(double begin, double end);
    /** Whole-interval typed source consensus. Legacy converted attributes do not implement it. */
    default RendererClass rendererClassBetween(double begin,double end){return RendererClass.unknown("source_unavailable");}
    Map<String, Object> summary();
}
