package dev.denza.vehiclecontrol.probe;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
public class WakeProbeTest {
    private JSONObject phrase(String text,double conf)throws Exception {JSONObject r=new JSONObject().put("text",text);JSONArray a=new JSONArray();String[] words=text.split(" ");for(int i=0;i<words.length;i++)a.put(new JSONObject().put("word",words[i]).put("conf",conf).put("start",i*0.4).put("end",i*0.4+0.35));return r.put("result",a);}
    @Test public void wakeRequiresWholePhoneticPhraseAndFiniteScores()throws Exception {
        assertTrue(WakePhrase.accepts(phrase("привет ден за",0.96)));assertTrue(WakePhrase.accepts(phrase("привет дэн за",0.96)));
        for(String s:List.of("привет","ден за","привет денис","привет ден за открой окно","[unk] привет ден за"))assertFalse(s,WakePhrase.accepts(phrase(s,1)));
        assertFalse(WakePhrase.verifies(phrase("привет где заправка",1)));
        assertFalse(WakePhrase.verifies(phrase("привет денис",1)));
        assertTrue(WakePhrase.verifies(phrase("привет день за",0.65)));
        assertFalse(WakePhrase.accepts(phrase("привет ден за",0.5)));assertFalse(WakePhrase.accepts(new JSONObject().put("text","привет ден за")));
        JSONObject r=phrase("привет ден за",1);r.getJSONArray("result").getJSONObject(1).put("start",3).put("end",3.4);assertFalse(WakePhrase.accepts(r));
    }
    @Test public void actualSyntheticRecognitionsRejectConfusablePhrases()throws Exception {
        try(java.io.InputStream in=getClass().getResourceAsStream("/wake-synthetic.json")) {
            assertNotNull(in);JSONArray rows=new JSONArray(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            for(int i=0;i<rows.length();i++) {JSONObject row=rows.getJSONObject(i);assertEquals(row.getString("phrase"),row.getBoolean("expected"),WakePhrase.matches(row.getJSONObject("candidate"),row.getJSONObject("verification")));}
        }
    }
    @Test public void actualCarNameSubstitutionsRequireIndependentNarrowConfirmation()throws Exception {
        try(java.io.InputStream in=getClass().getResourceAsStream("/wake-live-name-variants.json")) {
            JSONArray rows=new JSONArray(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);assertTrue(WakePhrase.matches(row.getJSONObject("candidate"),row.getJSONObject("verification")));assertFalse(WakePhrase.matches(phrase("привет ден за",0.5),row.getJSONObject("verification")));}
        }
        assertFalse(WakePhrase.matches(phrase("привет ден за",1),phrase("привет денис",1)));
        assertFalse(WakePhrase.matches(phrase("привет ден за",1),phrase("привет где заправка",1)));
    }
    @Test public void normalizationPreservesShapeWithoutClipping() {
        assertArrayEquals(new short[]{-12000,6000,0},CabinSpeech.normalize(new short[]{-30000,15000,0}));
        assertArrayEquals(new short[]{-800,400},CabinSpeech.normalize(new short[]{-100,50}));
    }
    @Test public void threeSamplesPerSeatAreRequiredAndReplacementPersists()throws Exception {
        SeatProfiles p=new SeatProfiles(null);double[][] e=new double[6][6];
        for(int i=0;i<6;i++){Arrays.fill(e[i],1);e[i][i]=100;}
        p.replace(0,List.of(e[0],e[0],e[0]));assertEquals(-1,p.guess(e[0]).seat());
        for(int i=1;i<6;i++)p.replace(i,List.of(e[i],e[i],e[i]));
        SeatProfiles restored=new SeatProfiles(p.json());assertEquals(6,restored.count());
        for(int i=0;i<6;i++){assertEquals(3,restored.samples(i));assertEquals(i,restored.guess(e[i]).seat());}
        assertEquals(-1,restored.guess(new double[]{1,1,1,1,1,1}).seat());
        restored.replace(1,List.of(e[0],e[0],e[0]));assertEquals(-1,restored.guess(e[0]).seat());
        try{restored.replace(0,List.of(e[0]));fail("requires three samples");}catch(IllegalArgumentException expected){}
    }
    @Test public void speechKeepsAlignmentAcrossShortReadsAndIgnoresReferenceChannels() {
        CabinSpeech gate=new CabinSpeech();List<CabinSpeech.Utterance> events=new ArrayList<>();
        short[] all=new short[16000*10*4];
        for(int f=0;f<64000;f++)for(int c=0;c<10;c++) {
            double t=f/16000.0;int value=(c==2||c==3||c==6||c==7)?8000:0;
            if(t>=1.2&&t<2.5&&c==8)value=(int)(900*Math.sin(f*0.16));all[f*10+c]=(short)value;
        }
        for(int i=0;i<all.length;i+=317){int n=Math.min(317,all.length-i);gate.add(Arrays.copyOfRange(all,i,i+n),n,events::add);}
        assertEquals(1,events.size());assertEquals(8,events.get(0).microphone());assertTrue(events.get(0).pcm().length>=16000);assertTrue(events.get(0).pcm().length<=80000);
    }
}
