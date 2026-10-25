package dev.denza.adaslink.yandexnative;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PublicRoadEvidenceTest {
    private static String catalog(String edges){return "{\"schema\":\""+PublicRoadEvidence.SCHEMA+"\",\"edges\":"+edges+"}";}
    @Test public void endpointsAreExactQuantizedUndirectedWithoutProximityOrPartialCoverage(){
        PublicRoadEvidence evidence=PublicRoadEvidence.parse(catalog("[[37.001,55,37,55],[37,55,37.001,55]]"));
        assertEquals(1,evidence.edgeCount());assertEquals(64,evidence.sha256().length());
        double[] lon={37,37.001,37.002},lat={55,55,55};
        assertTrue(evidence.covers(lon,lat,0,1));assertTrue(evidence.covers(lon,lat,0.25,0.75));
        assertFalse(evidence.covers(lon,lat,0,2));assertFalse(evidence.covers(lon,lat,0.5,1.5));
        assertFalse(evidence.covers(lon,lat,1,2));assertFalse(PublicRoadEvidence.EMPTY.covers(lon,lat,0,1));
        assertTrue(evidence.covers(new double[]{37.00000001,37.00100001},new double[]{55,55},0,1));
        assertFalse(evidence.covers(lon,new double[]{55.00000006,55.00000006,55},0,1));
        PublicRoadEvidence half=PublicRoadEvidence.parse(catalog("[[37,55,37.0005,55]]"));
        assertFalse(half.covers(lon,lat,0,0.5)); // matching part of an unconfirmed source edge is insufficient
        PublicRoadEvidence negative=PublicRoadEvidence.parse(catalog("[[-1.00000005,-1,-2,-2]]"));
        assertTrue(negative.covers(new double[]{-1.00000005,-2},new double[]{-1,-2},0,1));
        try{evidence.provenance().put("edges",999);fail("mutable provenance");}catch(UnsupportedOperationException expected){}
    }
    @Test public void invalidCatalogRejectsWholeInputAndIsBounded(){
        for(String value:new String[]{"{}", "[]", "{\"schema\":\"wrong\",\"edges\":[]}",
                catalog("null"),catalog("[[37,55,37.001]]"),catalog("[[37,55,\"37.001\",55]]"),
                catalog("[[37,55,181,55]]"),catalog("[[37,55,37,91]]"),catalog("[[37,55,1e999,55]]"),
                catalog("[[37,55,37.000000001,55]]"),catalog("[[37,55,37.001,55],[37,55,999,55]]")}){
            try{PublicRoadEvidence.parse(value);fail("accepted invalid catalog: "+value);}catch(IllegalArgumentException expected){}
        }
        StringBuilder edges=new StringBuilder("[");
        for(int i=0;i<10001;i++){if(i>0)edges.append(',');edges.append("[37,55,37.001,55]");}
        edges.append(']');
        try{PublicRoadEvidence.parse(catalog(edges.toString()));fail("raw duplicate records still count toward input bound");}catch(IllegalArgumentException expected){}
        assertEquals(0,PublicRoadEvidence.parse(catalog("[]")).edgeCount());
    }
}
