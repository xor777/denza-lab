package dev.denza.adaslink.yandexnative;

/** Names and values from the pinned original APK's AutoSDK model classes (classes19.dex).
 * Only semantically corresponding Yandex Action names are mapped. Fork/exit instructions
 * are not silently recoded as slight turns. No legacy converter is consulted. */
public final class NativeEnums {
    public static final int ROAD_NULL=-1, FORMWAY_NULL=-1, LINK_NULL=-1;
    public static final int OWNERSHIP_ERROR=Integer.MIN_VALUE;
    public static final int LINK_COMMON=0, LINK_FERRY=1, LINK_TUNNEL=2;
    public static final int FORMWAY_ROUNDABOUT=4;
    public static final class Action {
        public final int main,assistant,icon;
        public final boolean mapped;
        Action(int main,int assistant,int icon,boolean mapped){this.main=main;this.assistant=assistant;this.icon=icon;this.mapped=mapped;}
    }
    public static Action action(String value){
        if(value==null)return new Action(0,0,0,false);
        switch(value){
            case "STRAIGHT":return new Action(8,0,9,true);
            case "LEFT":return new Action(1,0,2,true);
            case "RIGHT":return new Action(2,0,3,true);
            case "SLIGHT_LEFT":return new Action(3,0,4,true);
            case "SLIGHT_RIGHT":return new Action(4,0,5,true);
            case "HARD_LEFT":return new Action(5,0,6,true);
            case "HARD_RIGHT":return new Action(6,0,7,true);
            case "UTURN_LEFT":return new Action(7,0,8,true);
            case "UTURN_RIGHT":return new Action(7,0,19,true);
            case "ENTER_ROUNDABOUT":return new Action(11,0,11,true);
            case "LEAVE_ROUNDABOUT":return new Action(12,0,12,true);
            case "BOARD_FERRY":return new Action(0,19,46,true);
            // The source SDK has no separate leave-ferry ManeuverIconID.
            case "LEAVE_FERRY":return new Action(0,20,0,true);
            case "WAYPOINT":return new Action(0,35,10,true);
            case "FINISH":return new Action(0,36,15,true);
            default:return new Action(0,0,0,false);
        }
    }
    private NativeEnums(){}
}
