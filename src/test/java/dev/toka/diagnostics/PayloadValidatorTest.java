package dev.toka.diagnostics;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;
public class PayloadValidatorTest {
 static byte[] emptyChunk(){var out=new ByteArrayOutputStream();for(int y=-4;y<0;y++){out.write(9);out.write(0);out.write(y&255);}for(int i=0;i<24;i++){out.write(1);out.write(0);}out.write(0);return out.toByteArray();}
 @Test public void validatesEmptyLegacyOverworld(){assertEquals("PASS",PayloadValidator.chunk(emptyChunk(),4,0).status());}
 @Test public void rejectsTruncatedPayload(){byte[] b=emptyChunk();assertEquals("FAIL",PayloadValidator.chunk(java.util.Arrays.copyOf(b,b.length-1),4,0).status());}
 @Test public void rejectsOutOfRangePaletteIndex(){var out=new ByteArrayOutputStream();out.writeBytes(new byte[]{9,1,0,3});for(int i=0;i<128;i++)out.writeBytes(i==0?new byte[]{1,0,0,0}:new byte[4]);out.write(2);out.write(0);var result=PayloadValidator.chunk(out.toByteArray(),1,0);assertEquals("FAIL",result.status());assertTrue(result.detail().contains("palette index=1 size=1"));}
 @Test public void unknownFormatsAreNotReportedValid(){byte[] b=emptyChunk();b[0]=8;assertEquals("UNSUPPORTED",PayloadValidator.chunk(b,4,0).status());}
 @Test public void checksNbtBoundaries(){assertEquals("PASS",PayloadValidator.nbt(new byte[]{10,0,0}).status());assertEquals("FAIL",PayloadValidator.nbt(new byte[]{10,0,8,0,5,65}).status());assertEquals("FAIL",PayloadValidator.nbt(new byte[]{10,0,0,1}).status());}
 @Test public void detectsNonContiguousSections(){byte[] b=emptyChunk();b[5]=1;assertTrue(PayloadValidator.chunk(b,4,0).detail().contains("non-contiguous"));}
 @Test public void unknownRuntimeDoesNotClaimClientInvalidity(){var out=new ByteArrayOutputStream();out.writeBytes(new byte[]{9,1,0,1,2});var r=PayloadValidator.chunk(out.toByteArray(),1,0,id->false);assertEquals("UNSUPPORTED",r.status());assertTrue(r.detail().contains("server palette missing"));}
}
