package com.dkalarm.app.core;
import java.time.*;
import java.util.*;
import java.util.regex.*;
public final class TableParser {
 public static final class Word {
  public final String text;public final int left,top,right,bottom;public final float confidence;
  public Word(String t,int l,int y,int r,int b,float c){text=t;left=l;top=y;right=r;bottom=b;confidence=c;}
 }
 public static final class Row {
  public String villageName="",coordinates="",arrivalText="",commandText="",warning="";
  public Rules.Noble noble=Rules.Noble.NO;
  public Instant arrival;
  public boolean nameUncertain;
  public int top,bottom,flagLeft,flagRight;
 }
 private static final Pattern COORD=Pattern.compile("\\(?([0-9]{3})\\s*[|Il/),]{1,2}\\s*([0-9]{3})\\)?");
 private static String join(List<Word> words,int start,int end){
  List<Word> inside=new ArrayList<>();for(Word w:words)if((w.left+w.right)/2>=start&&(w.left+w.right)/2<end)inside.add(w);
  inside.sort(Comparator.comparingInt(w->w.left));StringJoiner s=new StringJoiner(" ");for(Word w:inside)s.add(w.text);return s.toString();
 }
 public Row parseRow(List<Word> words,TableGeometry g,TableGeometry.Band band,LocalDate date,ZoneId zone){
  Row r=new Row();r.top=band.top;r.bottom=band.bottom;
  r.commandText=join(words,g.left,g.targetStart);
  r.noble=Rules.noble(r.commandText);
  String target=join(words,g.targetStart,g.sourceStart);
  // Numeric coordinates have a fixed 3+3 format. Restrict glyph normalization to parentheses.
  Matcher pair=Pattern.compile("\\(([0-9SOIlBso]{3})[\\s|/1,)]*([0-9SOIlBso]{3})\\)").matcher(target);
  StringBuffer normalized=new StringBuffer();
  while(pair.find()) {
    String value="("+pair.group(1)+"|"+pair.group(2)+")";
    value=value.replace('S','5').replace('s','5').replace('O','0').replace('o','0').replace('I','1').replace('l','1').replace('B','8');
    pair.appendReplacement(normalized,Matcher.quoteReplacement(value));
  }
  pair.appendTail(normalized);target=normalized.toString();
  Matcher coord=COORD.matcher(target);
  if(coord.find()) {r.coordinates=coord.group(1)+"|"+coord.group(2);r.villageName=target.substring(0,coord.start()).trim();}
  else {r.villageName=target.replaceAll("\\s*\\(.*$","").replaceAll("\\s+K[0-9]{2}.*$","").trim();r.nameUncertain=true;}
  if(r.villageName.isEmpty()){r.villageName="Název nerozpoznán"+(r.coordinates.isEmpty()?"":" ("+r.coordinates+")");r.nameUncertain=true;}
  for(Word w:words)if(w.left>=g.targetStart&&w.right<g.sourceStart&&w.confidence>=0&&w.confidence<.65)r.nameUncertain=true;
  r.arrivalText=join(words,g.arrivalStart,g.arrivalEnd);
  // Do not drop a recognized row when its arrival time is unreadable.
  if(target.trim().isEmpty()&&r.commandText.trim().isEmpty())return null;
  Rules.Arrival parsed=Rules.arrival(r.arrivalText,date,zone);r.arrival=parsed.instant;r.warning=parsed.warning;
  if(r.coordinates.isEmpty()&&Rules.hms(r.arrivalText).isEmpty()&&
      (r.commandText.trim().isEmpty()||Rules.normalize(r.commandText).equals("kombinovane")))return null;
  if(r.noble==Rules.Noble.MAYBE)r.warning+=" Text jednotky je NEJISTÝ / ZKONTROLOVAT.";
  if(r.nameUncertain)r.warning+=" Název cílové vesnice zkontroluj.";
  // Flag is to the left of the command text. Only green/red saturated pixels are trusted.
  int textLeft=g.targetStart;
  for(Word w:words)if(w.left>=g.left&&w.right<=g.targetStart&&w.text.length()>=4&&w.text.codePoints().anyMatch(Character::isLetter))textLeft=Math.min(textLeft,w.left);
  r.flagLeft=Math.max(g.left,textLeft-(band.bottom-band.top)*2);r.flagRight=textLeft;
  return r;
 }
}
