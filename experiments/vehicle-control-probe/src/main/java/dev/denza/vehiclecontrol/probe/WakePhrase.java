package dev.denza.vehiclecontrol.probe;

import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Phonetic word boundaries are internal. The spoken phrase is «Привет, Денза». */
final class WakePhrase {
    static final String GRAMMAR = "[\"привет ден за\",\"привет дэн за\",\"[unk]\"]";
    static String reason(JSONObject result,boolean full) {
        String text=result.optString("text", "");
        boolean phrase=text.equals("привет ден за")||text.equals("привет дэн за")||(full&&text.equals("привет день за"));
        if(!phrase)return text.isBlank()?"слов не разобрано":"другая фраза: «"+text+"»";
        JSONArray words=result.optJSONArray("result");if(words==null)return "нет оценок слов";
        double threshold=full?0.5:0.75;
        for(int i=0;i<words.length();i++){JSONObject w=words.optJSONObject(i);if(w==null)continue;double c=w.optDouble("conf",0);if(c<threshold)return String.format(Locale.ROOT,"слово «%s»: %.2f ниже порога %.2f",w.optString("word"),c,threshold);}
        return "паузы или длительность фразы не подходят";
    }
    static boolean matches(JSONObject narrow,JSONObject full) {
        if(!accepts(narrow))return false;
        if(verifies(full))return true;
        String text=full.optString("text","");
        if(!text.equals("привет бензо")&&!text.equals("привет энзо"))return false;
        JSONArray words=full.optJSONArray("result"),candidate=narrow.optJSONArray("result");
        if(words==null||words.length()!=2||candidate==null||candidate.length()!=3)return false;
        JSONObject hello=words.optJSONObject(0),name=words.optJSONObject(1);
        if(hello==null||name==null||!"привет".equals(hello.optString("word"))||!text.endsWith(" "+name.optString("word")))return false;
        double a=hello.optDouble("conf",Double.NaN),b=name.optDouble("conf",Double.NaN);
        double start=name.optDouble("start",Double.NaN),end=name.optDouble("end",Double.NaN);
        double referenceStart=candidate.optJSONObject(1).optDouble("start",Double.NaN),referenceEnd=candidate.optJSONObject(2).optDouble("end",Double.NaN);
        return Double.isFinite(a)&&a>=0.85&&Double.isFinite(b)&&b>=0.35&&Double.isFinite(start)&&Double.isFinite(end)
                &&end>start&&Math.abs(start-referenceStart)<=0.3&&Math.abs(end-referenceEnd)<=0.25;
    }
    static boolean accepts(JSONObject result) { return assess(result,0.75,false); }
    static boolean verifies(JSONObject result) { return assess(result,0.5,true); }
    private static boolean assess(JSONObject result,double minimum,boolean full) {
        String text=result.optString("text", "").trim().toLowerCase(Locale.ROOT);
        if (!text.equals("привет ден за") && !text.equals("привет дэн за") && !(full && text.equals("привет день за"))) return false;
        JSONArray words=result.optJSONArray("result");
        if(words==null || words.length()!=3) return false;
        String[] expected=text.split(" "); double previousEnd=-1, first=0;
        for(int i=0;i<3;i++) {
            JSONObject word=words.optJSONObject(i); if(word==null)return false;
            double confidence=word.optDouble("conf",Double.NaN),start=word.optDouble("start",Double.NaN),end=word.optDouble("end",Double.NaN);
            if(!expected[i].equals(word.optString("word")) || !Double.isFinite(confidence) || confidence<minimum
                    || !Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||start<previousEnd-0.02
                    ||(i>0 && start-previousEnd>0.55))return false;
            if(i==0)first=start;previousEnd=end;
        }
        return previousEnd-first>=0.45 && previousEnd-first<=2.8;
    }
}
