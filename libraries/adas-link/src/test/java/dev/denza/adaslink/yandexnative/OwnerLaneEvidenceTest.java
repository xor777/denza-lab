package dev.denza.adaslink.yandexnative;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerLaneEvidenceTest {
    private static String catalog(String laneNum,String edges){return "{\"schema\":\""+OwnerLaneEvidence.SCHEMA+"\",\"laneNum\":"+laneNum+",\"edges\":"+edges+"}";}
    @Test public void onlyConfirmedTwoLaneEdgesMatchExactWholeSourceEdges(){
        OwnerLaneEvidence lanes=OwnerLaneEvidence.parse(catalog("2","[[37.001,55,37,55],[37,55,37.001,55]]"));
        double[] lon={37,37.001,37.002},lat={55,55,55};
        assertEquals(1,lanes.edgeCount());assertEquals(64,lanes.sha256().length());
        assertTrue(lanes.covers(lon,lat,0,1));assertTrue(lanes.covers(lon,lat,0.25,0.75));
        assertFalse(lanes.covers(lon,lat,0,2));assertFalse(lanes.covers(lon,lat,0.5,1.5));assertFalse(lanes.covers(lon,lat,1,2));
        assertFalse(lanes.covers(lon,new double[]{55.00000006,55.00000006,55},0,1));
        assertFalse(OwnerLaneEvidence.EMPTY.covers(lon,lat,0,1));assertEquals(2,lanes.provenance().get("laneNum"));
        assertTrue(lanes.provenance().get("source").toString().contains("owner-observed"));
        try{lanes.provenance().put("laneNum",4);fail("mutable provenance");}catch(UnsupportedOperationException expected){}
    }
    @Test public void wrongSchemaMissingOrDifferentLaneCountAndInvalidCoordinatesRejectWholeCatalog(){
        for(String count:new String[]{"null","1","3","2.1","\"2\"","true"})
            rejected(catalog(count,"[[37,55,37.001,55]]"));
        rejected("{\"schema\":\""+OwnerLaneEvidence.SCHEMA+"\",\"edges\":[]}");
        rejected(catalog("2","[[37,55,37.001,55]]").replace(OwnerLaneEvidence.SCHEMA,PublicRoadEvidence.SCHEMA));
        rejected(catalog("2","[[37,55,37.001,55],[37,55,37.002,91]]"));
        rejected(catalog("2","[[37,55,1e999,55]]"));rejected(catalog("2","[[37,55,37,55]]"));
        assertEquals(0,OwnerLaneEvidence.parse(catalog("2","[]")).edgeCount());
    }
    private static void rejected(String json){try{OwnerLaneEvidence.parse(json);fail("accepted invalid lane catalog");}catch(IllegalArgumentException expected){}}
}
