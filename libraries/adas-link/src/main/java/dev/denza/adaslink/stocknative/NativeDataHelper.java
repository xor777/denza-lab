package dev.denza.adaslink.stocknative;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.math.BigDecimal;
import static dev.denza.adaslink.stocknative.NativeModels.*;
/** Original camera helpers and TTS detection k.h.a0.d.c (no adapter interpretation). */
final class NativeDataHelper {
    static boolean empty(Collection<?> value){return value==null||value.isEmpty();}
    public static List<NaviCameraExt> a(List<NaviCameraExt> list, int i2) {
        ArrayList arrayList = new ArrayList();
        Iterator<NaviCameraExt> it = list.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            NaviCameraExt next = it.next();
            NaviSubCameraExt naviSubCameraExtB = b(next);
            if (naviSubCameraExtB != null && !empty(next.subCameras)) {
                if (next.distance < i2) {
                    ArrayList arrayList2 = new ArrayList(1);
                    arrayList2.add(naviSubCameraExtB);
                    arrayList.add(new NaviCameraExt(next.cameraId, next.coord2D, next.coord3D, next.distance, next.segmentIndex, next.linkIndex, next.distanceToEnd, next.roadClass, next.isHidden, arrayList2));
                    break;
                }
            }
        }
        return arrayList;
    }

    public static NaviSubCameraExt b(NaviCameraExt naviCameraExt) {
        short sShortValue;
        short sShortValue2;
        NaviSubCameraExt naviSubCameraExt = null;
        NaviSubCameraExt naviSubCameraExt2 = null;
        NaviSubCameraExt naviSubCameraExt3 = null;
        for (NaviSubCameraExt naviSubCameraExt4 : new CopyOnWriteArrayList<NaviSubCameraExt>(naviCameraExt.subCameras)) {
            int i2 = naviSubCameraExt4.subType;
            if (i2 == 25) {
                naviSubCameraExt = naviSubCameraExt4;
            } else if (i2 == 26) {
                naviSubCameraExt2 = naviSubCameraExt4;
            } else if (!empty(naviSubCameraExt4.speed)) {
                naviSubCameraExt3 = naviSubCameraExt4;
            }
        }
        if (naviSubCameraExt != null) {
            if (naviSubCameraExt3 != null && !naviSubCameraExt3.speed.isEmpty()) {
                CopyOnWriteArrayList copyOnWriteArrayList = new CopyOnWriteArrayList(naviSubCameraExt3.speed);
                short sShortValue3 = ((Short) Collections.min(copyOnWriteArrayList)).shortValue();
                ArrayList<Short> arrayList = naviSubCameraExt.speed;
                if (arrayList != null && !arrayList.isEmpty() && (sShortValue2 = ((Short) Collections.min(new CopyOnWriteArrayList(naviSubCameraExt.speed))).shortValue()) >= sShortValue3 && sShortValue2 > sShortValue3) {
                    naviSubCameraExt.speed = new ArrayList<>(copyOnWriteArrayList);
                }
            }
            return naviSubCameraExt;
        }
        if (naviSubCameraExt2 == null) {
            return naviSubCameraExt3;
        }
        if (naviSubCameraExt3 != null && !naviSubCameraExt3.speed.isEmpty()) {
            CopyOnWriteArrayList copyOnWriteArrayList2 = new CopyOnWriteArrayList(naviSubCameraExt3.speed);
            short sShortValue4 = ((Short) Collections.min(copyOnWriteArrayList2)).shortValue();
            ArrayList<Short> arrayList2 = naviSubCameraExt2.speed;
            if (arrayList2 != null && !arrayList2.isEmpty() && (sShortValue = ((Short) Collections.min(new CopyOnWriteArrayList(naviSubCameraExt2.speed))).shortValue()) >= sShortValue4 && sShortValue > sShortValue4) {
                naviSubCameraExt2.speed = new ArrayList<>(copyOnWriteArrayList2);
            }
        }
        return naviSubCameraExt2;
    }

    public static boolean f(String str) {
        return str.contains("长实线");
    }

    public static int g(ArrayList<NaviSubCameraExt> arrayList) {
        int i2 = 0;
        if (arrayList != null && !arrayList.isEmpty()) {
            int i3 = arrayList.get(0).priority;
            i2 = arrayList.get(0).subType;
            for (NaviSubCameraExt naviSubCameraExt : arrayList) {
                int i4 = naviSubCameraExt.priority;
                if (i4 > i3) {
                    i2 = naviSubCameraExt.subType;
                    i3 = i4;
                }
            }
        }
        return i2;
    }

    public static int h(String str){
        if(str.contains("长实线")&&str.contains("米")){int index=str.indexOf("米");if(index!=-1){BigDecimal value=NativeChineseNumbers.a(str.substring(0,index));return value==null?0:value.intValue();}}
        if(str.contains("长实线")&&str.contains("公里")){int index=str.indexOf("公里");if(index==0)return 0;if(index!=-1){BigDecimal value=NativeChineseNumbers.e(str.substring(0,index));return (int)((value==null?0.0:value.doubleValue())*1000.0);}}
        return 0;
    }

}
