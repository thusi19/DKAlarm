import com.dkalarm.app.core.*;
import java.time.*;
import java.util.*;
public class CoreTests {
 static int count=0;
 static void eq(Object expected,Object actual){count++;if(!Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+" got "+actual);}
 static Rules.Event e(String id,String village,long time){return new Rules.Event(id,village,time);}
 public static void main(String[] args){
  eq(Rules.Noble.YES,Rules.noble("Šlechta"));eq(Rules.Noble.YES,Rules.noble("Slechta"));
  eq(Rules.Noble.MAYBE,Rules.noble("Siechta"));eq(Rules.Noble.NO,Rules.noble("Beranidlo"));
  eq(Rules.Noble.NO,Rules.noble("Beranidio"));eq(Rules.Noble.MAYBE,Rules.noble("👑"));
  eq(Rules.Noble.MAYBE,Rules.noble(""));eq(Rules.Noble.NO,Rules.noble("Útok"));
  eq("14:35:55",Rules.hms("14:35:55:880"));eq("14:35:55",Rules.hms("14:35:55:??"));
  eq("",Rules.hms("29:20:43"));eq("",Rules.hms("1.4:34:03"));eq("",Rules.hms("15:1 6:47:40"));
  eq("14:35:55",Rules.hms("14:35:55850"));
  eq("14:35:55",Rules.hms("14.35.55:???"));
  eq(Rules.Noble.YES,Rules.nobleEvidence("Sechta","ORSechta"));
  eq(Rules.Noble.MAYBE,Rules.nobleEvidence("Sechta","Beranidlo"));
  eq(Rules.Noble.MAYBE,Rules.nobleEvidence("nešlechta","nešlechta"));
  eq(Rules.Noble.MAYBE,Rules.nobleEvidence("👑","👑"));
  LocalDate date=LocalDate.of(2026,9,10);ZoneId zone=ZoneId.of("Europe/Prague");
  eq(Instant.parse("2026-09-11T12:35:55Z"),Rules.arrival("zítra v 14:35:55:880",date,zone).instant);
  eq(Instant.parse("2026-09-10T12:35:55Z"),Rules.arrival("dnes v 14:35:55",date,zone).instant);
  eq(Instant.parse("2026-09-15T12:35:55Z"),Rules.arrival("dne 15.09. v 14:35:55",date,zone).instant);
  eq(null,Rules.arrival("dne 31.02. v 14:35:55",date,zone).instant);
  eq(null,Rules.arrival("dne 25.10.2026 v 02:30:00",date,zone).instant);
  eq(true,!Rules.arrival("14:35:55",date,zone).warning.isEmpty());
  eq("ŠLECHTA + ZELENÝ ÚTOK",Rules.label(true,Rules.Color.GREEN));
  eq("ŠLECHTA + ČERVENÝ ÚTOK",Rules.label(true,Rules.Color.RED));
  eq(true,Rules.important(true,Rules.Color.GREEN));eq(false,Rules.important(false,Rules.Color.GREEN));
  eq(true,Rules.important(false,Rules.Color.BROWN));
  List<Rules.Event> events=Arrays.asList(e("a1","a",100),e("a2","a",160),e("a3","a",220),e("a4","a",280),e("a5","a",340),e("b1","b",160),e("b2","b",220),e("b3","b",280));
  eq(Set.of("a1","a3","a5","b1","b3"),Rules.enabled(events));
  List<Rules.Event> reversed=new ArrayList<>(events);Collections.reverse(reversed);eq(Rules.enabled(events),Rules.enabled(reversed));
  eq(Set.of("1","3"),Rules.enabled(Arrays.asList(e("1","v",100),e("2","v",155),e("3","v",220))));
  eq(Set.of("1","2","3"),Rules.enabled(Arrays.asList(e("1","v",100),e("2","v",154),e("3","v",220))));
  eq(Set.of("1","1b","3"),Rules.enabled(Arrays.asList(e("1","v",100),e("1b","v",100),e("2","v",160),e("3","v",220))));
  eq("558|423",Rules.villageKey("Kaštan013","558|423"));
  // Noble in SOURCE column must not classify an ordinary attack as noble.
  TableGeometry g=new TableGeometry();g.left=0;g.targetStart=100;g.sourceStart=300;g.arrivalStart=500;g.arrivalEnd=650;
  List<TableParser.Word> words=Arrays.asList(w("Beranidlo",30,90),w("Kaštan013",110,190),w("(558|423)",195,260),w("Šlechta",310,360),w("(421|550)",365,440),w("zítra v 15:20:43:880",510,645),w("22:00:00",660,700));
  TableParser.Row row=new TableParser().parseRow(words,g,new TableGeometry.Band(0,20),date,zone);
  eq(Rules.Noble.NO,row.noble);eq("Kaštan013",row.villageName);eq("558|423",row.coordinates);eq("15:20:43",Rules.hms(row.arrivalText));
  eq(Instant.parse("2026-09-11T13:20:43Z"),row.arrival);
  System.out.println("PASS: "+count+" assertions");
 }
 static TableParser.Word w(String text,int l,int r){return new TableParser.Word(text,l,5,r,15,.99f);}
}
