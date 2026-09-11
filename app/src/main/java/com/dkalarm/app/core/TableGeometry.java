package com.dkalarm.app.core;
import java.util.*;

/** Geometry for the beige desktop incoming table, including landscape and cropped headers. */
public final class TableGeometry {
 public static final class Band { public final int top,bottom;public Band(int t,int b){top=t;bottom=b;} }
 public final List<Band> bands=new ArrayList<>();
 public int left,right,targetStart,sourceStart,arrivalStart,arrivalEnd;
 public boolean valid;
 private static boolean beige(int p){int r=(p>>16)&255,g=(p>>8)&255,b=p&255;return r>180&&g>150&&b<235&&r-b>15;}
 public static TableGeometry detect(int[] pixels,int width,int height){
  TableGeometry o=new TableGeometry();
  int[] shades=new int[height];Arrays.fill(shades,-1);
  List<Integer> tableY=new ArrayList<>();
  for(int y=0;y<height;y++){
   List<Integer> greens=new ArrayList<>();
   for(int x=0;x<width;x+=3){int p=pixels[y*width+x];if(beige(p))greens.add((p>>8)&255);}
   if(greens.size()<width/3*.40)continue;
   Collections.sort(greens);int green=greens.get(greens.size()/2);
   if(green<218)continue;
   shades[y]=green>=235?1:0;tableY.add(y);
  }
  if(tableY.isEmpty())return o;
  for(int start=0;start<height;){
   int end=start+1;while(end<height&&shades[end]==shades[start])end++;
   if(shades[start]>=0&&end-start>=10&&end-start<=80)o.bands.add(new Band(start,end));
   start=end;
  }
  if(o.bands.size()<3)return o;
  // Merge a row split by a few noisy scanlines; select the longest contiguous table run.
  List<Integer> heights=new ArrayList<>();for(Band b:o.bands)heights.add(b.bottom-b.top);
  Collections.sort(heights);int typical=heights.get(heights.size()*3/4);
  List<Band> merged=new ArrayList<>();
  for(int i=0;i<o.bands.size();i++){
   Band b=o.bands.get(i);
   if(i+1<o.bands.size()){
    Band next=o.bands.get(i+1);
    if(b.bottom-b.top<typical*.7 && next.bottom-next.top<typical*.7 && next.top-b.bottom<=4 && next.bottom-b.top<typical*1.25){
     b=new Band(b.top,next.bottom);i++;
    }
   }merged.add(b);
  }
  List<Band> best=new ArrayList<>(),run=new ArrayList<>();
  for(Band b:merged){
   if(!run.isEmpty()&&b.top-run.get(run.size()-1).bottom>typical*2.5){if(run.size()>best.size())best=new ArrayList<>(run);run.clear();}
   run.add(b);
  }
  if(run.size()>best.size())best=run;
  o.bands.clear();o.bands.addAll(best);
  if(o.bands.size()<3)return o;
  int sampleY=(o.bands.get(o.bands.size()/2).top+o.bands.get(o.bands.size()/2).bottom)/2;
  o.left=0;while(o.left<width&&!beige(pixels[sampleY*width+o.left]))o.left++;
  o.right=width;while(o.right>o.left&&!beige(pixels[sampleY*width+o.right-1]))o.right--;
  double[] scores=new double[width];
  for(int x=2;x<width-2;x++){
   int n=0;for(int y:tableY){int c=pixels[y*width+x],a=pixels[y*width+x-2],b=pixels[y*width+x+2];
    if(beige(c)&&Math.max(luma(a),luma(b))-luma(c)>4)n++;
   }scores[x]=(double)n/tableY.size();
  }
  o.targetStart=o.border(scores,.09,.17);o.sourceStart=o.border(scores,.30,.45);
  boolean wideSource=(double)(o.sourceStart-o.left)/(o.right-o.left)>.395;
  o.arrivalStart=wideSource?o.border(scores,.86,.90):o.border(scores,.72,.81);
  o.arrivalEnd=wideSource?o.right:o.border(scores,.83,.91);
  o.valid=scores[o.targetStart]>.10&&scores[o.sourceStart]>.10&&scores[o.arrivalStart]>.10&&(o.arrivalEnd==o.right||scores[o.arrivalEnd]>.10);
  return o;
 }
 private static double luma(int p){return (((p>>16)&255)+((p>>8)&255)+(p&255))/3.;}
 private int border(double[] scores,double from,double to){
  int a=(int)(left+(right-left)*from),b=(int)(left+(right-left)*to),best=a;
  for(int x=a;x<b;x++)if(scores[x]>scores[best])best=x;return best;
 }
}
