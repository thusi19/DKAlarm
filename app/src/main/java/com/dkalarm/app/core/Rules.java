package com.dkalarm.app.core;

import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Shared, Android-independent rules. Never examines crown pixels. */
public final class Rules {
    private Rules() {}
    public enum Noble { YES, MAYBE, NO }
    public enum Color { GREEN, RED, BROWN, UNKNOWN }
    public static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
    public static Noble noble(String commandText) {
        boolean knownOther = false;
        for (String token : normalize(commandText).split("[^a-z0-9]+")) {
            if (token.equals("slechta")) return Noble.YES;
            if (editDistance(token, "beranidlo") <= 1 || token.equals("utok")) knownOther = true;
        }
        return knownOther ? Noble.NO : Noble.MAYBE;
    }
    /** Only command-column text; fuzzy spelling requires a second OCR rendering. */
    public static Noble nobleEvidence(String clean, String original) {
        Noble direct=noble(clean);
        if(direct!=Noble.MAYBE)return direct;
        String other=normalize(original).replaceAll("[^a-z]", "");
        for(String token:normalize(clean).split("[^a-z]+")) {
            if((token.equals("sechta")||token.equals("siechta")||token.equals("s1echta")) &&
               (other.endsWith(token)||other.endsWith(token+"a")||noble(original)==Noble.YES))return Noble.YES;
        }
        return Noble.MAYBE;
    }
    private static int editDistance(String a, String b) {
        int[] p = new int[b.length()+1];
        for (int j=0;j<p.length;j++) p[j]=j;
        for (int i=1;i<=a.length();i++) {
            int[] q=new int[p.length]; q[0]=i;
            for(int j=1;j<q.length;j++) q[j]=Math.min(Math.min(q[j-1]+1,p[j]+1),p[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
            p=q;
        }
        return p[b.length()];
    }
    // Consume a fractional suffix, but expose only whole seconds. No millisecond validation.
    public static final Pattern TIME = Pattern.compile("(?<![0-9:.,])([01]?[0-9]|2[0-3])[:.]([0-5][0-9])[:.]([0-5][0-9])");
    private static final Pattern DATE = Pattern.compile("(?<![0-9])([0-3]?[0-9])\\.\\s*([01]?[0-9])\\.(?:\\s*(20[0-9]{2}))?");
    public static String hms(String s) {
        Matcher m=TIME.matcher(s);
        if (!m.find()) return "";
        if (s.substring(0,m.start()).matches("(?s).*\\d\\s*$")) return "";
        return String.format(Locale.ROOT,"%02d:%s:%s",Integer.parseInt(m.group(1)),m.group(2),m.group(3));
    }
    public static final class Arrival {
        public final Instant instant;
        public final String warning;
        public Arrival(Instant i,String w) {instant=i;warning=w;}
    }
    public static Arrival arrival(String text, LocalDate screenshotDate, ZoneId zone) {
        try {
            int divider=normalize(text).lastIndexOf('v');
            String clock=divider>=0?text.substring(divider+1):text;
            String datePart=divider>=0?text.substring(0,divider):"";
            String time=hms(clock);
            if(time.isEmpty()) return new Arrival(null,"Čas Příchod nerozpoznán.");
            String n=normalize(text);
            LocalDate date=screenshotDate;
            Matcher dm=DATE.matcher(datePart);
            boolean explicit=false;
            if(dm.find()) {
                date=LocalDate.of(dm.group(3)==null?screenshotDate.getYear():Integer.parseInt(dm.group(3)),Integer.parseInt(dm.group(2)),Integer.parseInt(dm.group(1)));
                explicit=true;
            } else if(n.contains("zitra")) { date=date.plusDays(1); explicit=true; }
            else if(n.contains("dnes")) explicit=true;
            LocalDateTime local=date.atTime(LocalTime.parse(time));
            if(zone.getRules().getValidOffsets(local).size()!=1) return new Arrival(null,"Čas je nejednoznačný kvůli změně letního času. Zkontroluj jej.");
            return new Arrival(local.atZone(zone).toInstant(),explicit?"":"Den příchodu nebyl čitelný. Potvrď datum příchodu.");
        } catch (RuntimeException e) { return new Arrival(null,"Neplatné datum nebo čas příchodu."); }
    }
    public static String label(boolean noble, Color color) {
        String name;
        switch(color) {
            case GREEN: name="ZELENÝ ÚTOK";break;
            case RED: name="ČERVENÝ ÚTOK";break;
            case BROWN: name="HNĚDÝ ÚTOK";break;
            default:name="ÚTOK – BARVA NEJISTÁ";
        }
        return (noble?"ŠLECHTA + ":"")+name;
    }
    public static boolean important(boolean noble,Color c) {return noble||c==Color.RED||c==Color.BROWN;}
    public static String villageKey(String name,String coords) {
        return coords==null||coords.isEmpty()?normalize(name).trim().replaceAll("\\s+"," "):coords;
    }
    public static final class Event {
        public final String id,village;
        public final long trigger;
        public Event(String id,String village,long trigger) {this.id=id;this.village=village;this.trigger=trigger;}
    }
    /** Same-second events are one audible slot. Chains are per village, 55..65 seconds. */
    public static Set<String> enabled(List<Event> events) {
        Map<String,TreeMap<Long,List<Event>>> groups=new HashMap<>();
        for(Event e:events) groups.computeIfAbsent(e.village,k->new TreeMap<>()).computeIfAbsent(e.trigger,k->new ArrayList<>()).add(e);
        Set<String> result=new HashSet<>();
        for(TreeMap<Long,List<Event>> series:groups.values()) {
            Long previous=null; int position=0;
            for(Map.Entry<Long,List<Event>> slot:series.entrySet()) {
                long gap=previous==null?Long.MAX_VALUE:slot.getKey()-previous;
                position=(gap>=55 && gap<=65)?position+1:0;
                if(position%2==0) for(Event e:slot.getValue()) result.add(e.id);
                previous=slot.getKey();
            }
        }
        return result;
    }
}
