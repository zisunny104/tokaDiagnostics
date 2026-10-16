package dev.toka.diagnostics;

/** Bounded structural reader for this Nukkit's network subchunks and network NBT. */
final class PayloadValidator {
 record Result(String status,String detail) {}
 static Result chunk(byte[] bytes,int sections,int dimension) {
  return chunk(bytes,sections,dimension,id->true);
 }
 static Result chunk(byte[] bytes,int sections,int dimension,java.util.function.IntPredicate knownRuntime) {
  Cursor c=new Cursor(bytes);
  c.knownRuntime=knownRuntime;
  try {
   if(sections<0||sections>64)throw new Invalid("section count="+sections);
   int previousY=-999;
   for(int s=0;s<sections;s++) {
    int version=c.u8();if(version!=9)return new Result("UNSUPPORTED","subchunkVersion="+version+" section="+s+" offset="+(c.at-1));
    int layers=c.u8();if(layers>2)throw new Invalid("layers="+layers+" section="+s);
    int y=(byte)c.u8();
    if(y<-4||y>31)throw new Invalid("sectionY="+y);
    if(s>0&&y!=previousY+1)throw new Invalid("non-contiguous sectionY="+y+" previous="+previousY);
    previousY=y;
    for(int layer=0;layer<layers;layer++)c.palette(true);
   }
   int biomes=switch(dimension){case 0->24;case 1->8;case 2->16;default->-1;};
   if(biomes<0)return new Result("UNSUPPORTED","dimension="+dimension+" blockSectionsParsed="+sections);
   for(int i=0;i<biomes;i++)c.palette(false);
   int border=c.u8();if(border!=0)return new Result("UNSUPPORTED","borderCount="+border+" offset="+(c.at-1));
   int entities=0;while(c.at<c.data.length){c.namedCompound();if(++entities>4096)throw new Invalid("too many block entities");}
   return new Result("PASS","sections="+sections+" biomes="+biomes+" blockEntities="+entities+" parsedBytes="+c.at);
  }catch(Unsupported e){return new Result("UNSUPPORTED",e.getMessage()+" offset="+c.at);}
   catch(Invalid e){return new Result("FAIL",e.getMessage()+" offset="+c.at);}
 }
 static Result nbt(byte[] bytes) {
  Cursor c=new Cursor(bytes);
  try {c.namedCompound();if(c.at!=c.data.length)throw new Invalid("trailing NBT bytes="+(c.data.length-c.at));return new Result("PASS","parsedBytes="+c.at);}
  catch(Unsupported e){return new Result("UNSUPPORTED",e.getMessage()+" offset="+c.at);}
  catch(Invalid e){return new Result("FAIL",e.getMessage()+" offset="+c.at);}
 }
 private static class Invalid extends RuntimeException {Invalid(String s){super(s);}}
 private static final class Unsupported extends Invalid {Unsupported(String s){super(s);}}
 private static final class Cursor {
  final byte[] data;int at;int budget=500000;
  java.util.function.IntPredicate knownRuntime=id->true;
  Cursor(byte[] data){this.data=data==null?new byte[0]:data;}
  void skip(int n){if(n<0||n>data.length-at)throw new Invalid("truncated length="+n);at+=n;}
  int u8(){if(at==data.length)throw new Invalid("unexpected end");return data[at++]&255;}
  int word(){int a=u8(),b=u8(),c=u8(),d=u8();return a|(b<<8)|(c<<16)|(d<<24);}
  int unsigned(){long v=0;for(int i=0;i<5;i++){int b=u8();v|=(long)(b&127)<<(i*7);if((b&128)==0){if(v>0xffffffffL)throw new Invalid("varint overflow");return (int)v;}}throw new Invalid("varint too long");}
  int signed(){int v=unsigned();return (v>>>1)^-(v&1);}
  int length(){int n=signed();if(n<0)throw new Invalid("negative NBT length="+n);if(n>262144)throw new Unsupported("NBT length budget="+n);return n;}
  void string(){int n=unsigned();skip(n);}
  void palette(boolean block) {
   int header=u8();if(header==255){if(block)throw new Invalid("block palette reuse header");throw new Unsupported("biome reuse header");}
   if((header&1)==0)throw new Unsupported("persistent palette in network payload");
   int bits=header>>>1;
   if(bits!=0&&bits!=1&&bits!=2&&bits!=3&&bits!=4&&bits!=5&&bits!=6&&bits!=8&&bits!=16)throw new Invalid("palette bits="+bits);
   int maxIndex=0;
   if(bits!=0) {
    int entries=32/bits,words=(4096+entries-1)/entries,mask=(1<<bits)-1,remaining=4096;
    for(int w=0;w<words;w++){int value=word();for(int j=0;j<entries&&remaining>0;j++,remaining--){maxIndex=Math.max(maxIndex,(value>>>(j*bits))&mask);}}
   }
   int count=bits==0?1:signed();
   if(count<1||count>65536||(bits!=0&&count>(1<<bits)))throw new Invalid("palette size="+count+" bits="+bits);
   if(maxIndex>=count)throw new Invalid("palette index="+maxIndex+" size="+count);
   for(int i=0;i<count;i++){int runtime=signed();if(runtime<0)throw new Invalid("negative runtime="+runtime);if(block&&!knownRuntime.test(runtime))throw new Unsupported("server palette missing runtime="+runtime);}
  }
  void namedCompound(){int type=u8();if(type!=10)throw new Invalid("NBT root type="+type);string();tag(10,0);}
  void tag(int type,int depth) {
   if(depth>64||--budget<0)throw new Unsupported("NBT depth/node budget exceeded");
   switch(type) {
    case 1->skip(1);case 2->skip(2);case 3->signed();
    case 4->{boolean end=false;for(int i=0;i<10;i++){int b=u8();if(i==9&&(b&254)!=0)throw new Invalid("NBT long overflow");if((b&128)==0){end=true;break;}}if(!end)throw new Invalid("NBT long too long");}
    case 5->skip(4);case 6->skip(8);case 7->skip(length());case 8->string();
    case 9->{int child=u8(),n=length();if(child<0||child>12||(child==0&&n!=0))throw new Invalid("NBT list type="+child);for(int i=0;i<n;i++)tag(child,depth+1);}
    case 10->{while(true){int child=u8();if(child==0)break;string();tag(child,depth+1);}}
    case 11->{int n=length();for(int i=0;i<n;i++)signed();}
    case 12->{int n=length();for(int i=0;i<n;i++)tag(4,depth+1);}
    default->throw new Invalid("NBT type="+type);
   }
  }
 }
}
