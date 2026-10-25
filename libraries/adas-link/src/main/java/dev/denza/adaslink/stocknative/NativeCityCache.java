package dev.denza.adaslink.stocknative;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArraySet;

/** m.i/a city-cache computation; m.b leaves this process-persistent city set intact. */
final class NativeCityCache {
    private final NativeEnvironment env;
    private final Set<Integer> cities=new CopyOnWriteArraySet<>();
    private final AtomicBoolean attempted=new AtomicBoolean(false);

    NativeCityCache(NativeEnvironment env){this.env=env;}

    Set<Integer> i(){
        if(!attempted.get()&&cities.isEmpty()){
            String value=env.cachedOpenCityJson();
            attempted.set(true);
            if(value==null||value.length()==0)return cities;
            // Original Gson builds the entire list before set.addAll. A parse
            // failure must not leave a partially populated process cache.
            cities.addAll(parseIntegers(value));
        }
        return cities;
    }

    void a(List<Integer> values){
        cities.clear();
        cities.addAll(values);
        env.writeCachedOpenCityJson(pretty(values));
    }

    /** Original Gson 2.9.1 JsonParser(lenient tree) -> Integer JsonTreeReader.
     * NUMBER lexemes use LazilyParsedNumber.intValue; STRINGs use parseInt.
     * The entire list is converted before addAll, including failure paths. */
    private static List<Integer> parseIntegers(String text){
        return new IntegerArrayReader(text).read();
    }

    /** Only Integer-array values can survive GsonUtils.jsonToList(...,Integer.class).
     * Nested containers and booleans fail conversion and never populate the set.
     * This reader preserves the accepted language without a Gson dependency. */
    private static final class IntegerArrayReader {
        private final String text;
        private int at;
        IntegerArrayReader(String text){this.text=text;}
        List<Integer> read(){
            if(text.length()>0&&text.charAt(0)=='\ufeff')at++;
            space(true);
            if(text.startsWith(")]}'\n",at))at+=5;
            space(true);expect('[');space(true);
            List<Integer> result=new ArrayList<>();
            if(peek()==']'){at++;end();return result;}
            while(true){
                space(true);
                char c=peek();
                // JsonReader's empty array literals (including a trailing
                // separator) become JsonNull without consuming the delimiter.
                if(c==','||c==';'||c==']')result.add(null);
                else if(c=='"'||c=='\'')result.add(Integer.valueOf(quoted()));
                else{
                    int start=at;
                    while(at<text.length()&&literal(text.charAt(at)))at++;
                    if(at==start)throw invalid();
                    String token=text.substring(start,at);
                    if(token.equalsIgnoreCase("null"))result.add(null);
                    else if(number(token))result.add(new BigDecimal(token).intValue());
                    else result.add(Integer.valueOf(token));
                }
                space(true);c=peek();at++;
                if(c==']')break;
                if(c!=','&&c!=';')throw invalid();
            }
            end();return result;
        }
        private static boolean number(String token){
            // Gson's 1024-char JsonReader buffer classifies longer numeric
            // literals as unquoted STRINGs, whose Integer conversion can fail.
            return token.length()<1024&&token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
        }
        private String quoted(){
            char quote=text.charAt(at++);StringBuilder value=new StringBuilder();
            while(at<text.length()){
                char c=text.charAt(at++);
                if(c==quote)return value.toString();
                if(c=='\\'){
                    if(at==text.length())throw invalid();
                    c=text.charAt(at++);
                    switch(c){
                        case 'u':
                            if(at+4>text.length())throw invalid();
                            int code=0;
                            for(int n=0;n<4;n++){
                                char digit=text.charAt(at++);
                                int hex=digit>='0'&&digit<='9'?digit-'0':digit>='a'&&digit<='f'?digit-'a'+10:digit>='A'&&digit<='F'?digit-'A'+10:-1;
                                if(hex<0)throw invalid();code=(code<<4)|hex;
                            }
                            c=(char)code;break;
                        case 'b':c='\b';break;
                        case 'f':c='\f';break;
                        case 'n':c='\n';break;
                        case 'r':c='\r';break;
                        case 't':c='\t';break;
                        case '\n':case '"':case '\'':case '/':case '\\':break;
                        default:throw invalid();
                    }
                }
                value.append(c);
            }
            throw invalid();
        }
        private void space(boolean comments){
            while(at<text.length()){
                char c=text.charAt(at);
                if(c==' '||c=='\t'||c=='\r'||c=='\n'){at++;continue;}
                if(!comments)return;
                if(c=='#'){at++;line();continue;}
                if(c=='/'&&at+1<text.length()){
                    char next=text.charAt(at+1);
                    if(next=='/'){at+=2;line();continue;}
                    if(next=='*'){
                        int end=text.indexOf("*/",at+2);
                        if(end<0)throw invalid();at=end+2;continue;
                    }
                }
                return;
            }
        }
        private void line(){while(at<text.length()){char c=text.charAt(at++);if(c=='\r'||c=='\n')return;}}
        private void end(){
            // JsonParser restores the reader's original strict mode before its
            // END_DOCUMENT check. Consequently trailing comments are rejected.
            space(false);if(at!=text.length())throw invalid();
        }
        private char peek(){if(at==text.length())throw invalid();return text.charAt(at);}
        private void expect(char c){if(peek()!=c)throw invalid();at++;}
        private static boolean literal(char c){
            switch(c){case ' ':case '\t':case '\r':case '\n':case '\f':case '/':case '\\':case ';':case '#':case '=':case '{':case '}':case '[':case ']':case ':':case ',':return false;default:return true;}
        }
        private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid original city-cache Integer array");}
    }
    private static String pretty(List<Integer> values){
        if(values.isEmpty())return "[]";
        StringBuilder out=new StringBuilder("[\n");
        for(int i=0;i<values.size();i++){if(i!=0)out.append(",\n");out.append("  ").append(values.get(i));}
        return out.append("\n]").toString();
    }
}
