import cn.nukkit.plugin.PluginBase;
import java.util.*;
public class SmokeProbe extends PluginBase {
 @Override public void onEnable() {
  try {
   Object plugin=getServer().getPluginManager().getPlugin("tokaDiagnostics");
   if(plugin==null || !((PluginBase)plugin).isEnabled()) throw new Exception("not enabled");
   if(!java.nio.file.Files.isDirectory(((PluginBase)plugin).getDataFolder().toPath().resolve("reports"))) throw new Exception("reports directory missing at startup");
   // Deliberately bypass construction to represent a pre-login Player with no UUID.
   // Unsafe is confined to this test fixture; production never uses it.
   var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
   var unsafe=(sun.misc.Unsafe)unsafeField.get(null);
   cn.nukkit.Player pending=(cn.nukkit.Player)unsafe.allocateInstance(cn.nukkit.Player.class);
   if(pending.getUniqueId()!=null) throw new Exception("fixture must have null UUID");
   var incoming=plugin.getClass().getMethod("received",cn.nukkit.event.server.DataPacketReceiveEvent.class);
   incoming.invoke(plugin,new cn.nukkit.event.server.DataPacketReceiveEvent(pending,new cn.nukkit.network.protocol.LoginPacket()));
   var outgoing=plugin.getClass().getMethod("sent",cn.nukkit.event.server.DataPacketSendEvent.class);
   outgoing.invoke(plugin,new cn.nukkit.event.server.DataPacketSendEvent(pending,new cn.nukkit.network.protocol.UpdateBlockPacket()));
   cn.nukkit.utils.BinaryStream network=new cn.nukkit.utils.BinaryStream();
   for(int y=-4;y<0;y++){network.putByte((byte)9);network.putByte((byte)0);network.putByte((byte)y);}
   network.putByte((byte)9);network.putByte((byte)1);network.putByte((byte)0);
   new cn.nukkit.level.format.leveldb.structure.StateBlockStorage().writeTo(network);
   for(int i=0;i<24;i++)cn.nukkit.level.util.PalettedBlockStorage.createWithDefaultState(1).writeTo(network);
   network.putByte((byte)0);
   cn.nukkit.network.protocol.LevelChunkPacket chunk=new cn.nukkit.network.protocol.LevelChunkPacket();
   chunk.subChunkCount=5;chunk.dimension=0;chunk.data=network.getBuffer();
   outgoing.invoke(plugin,new cn.nukkit.event.server.DataPacketSendEvent(pending,chunk));
   cn.nukkit.network.protocol.BlockEntityDataPacket blockEntity=new cn.nukkit.network.protocol.BlockEntityDataPacket();
   blockEntity.namedTag=cn.nukkit.nbt.NBTIO.write(new cn.nukkit.nbt.tag.CompoundTag().putString("id","Sign").putInt("x",1).putLong("test",-123L).putByteArray("bytes",new byte[]{1,2,3}),java.nio.ByteOrder.LITTLE_ENDIAN,true);
   outgoing.invoke(plugin,new cn.nukkit.event.server.DataPacketSendEvent(pending,blockEntity));
   var finishPending=plugin.getClass().getDeclaredMethod("finish",cn.nukkit.Player.class,String.class);finishPending.setAccessible(true);finishPending.invoke(plugin,null,"QUIT");
   getLogger().info("PRELOGIN_NULL_UUID_PASS");
   var field=plugin.getClass().getDeclaredField("traces");field.setAccessible(true);
   if(((Map)field.get(plugin)).size()!=1) throw new Exception("automatic tracing failed");
   cn.nukkit.Player second=(cn.nukkit.Player)unsafe.allocateInstance(cn.nukkit.Player.class);
   incoming.invoke(plugin,new cn.nukkit.event.server.DataPacketReceiveEvent(second,new cn.nukkit.network.protocol.LoginPacket()));
   if(((Map)field.get(plugin)).size()!=2) throw new Exception("prelogin connections must stay separate");
   var finish=plugin.getClass().getDeclaredMethod("finish",cn.nukkit.Player.class,String.class);finish.setAccessible(true);finish.invoke(plugin,pending,"SMOKE");
   finish.invoke(plugin,second,"SMOKE");
   getLogger().info("DIAGNOSTICS_SMOKE_PASS");
  } catch(Exception e) {getLogger().error("DIAGNOSTICS_SMOKE_FAIL",e);}
  getServer().getScheduler().scheduleDelayedTask(this,()->getServer().shutdown(),40);
 }
}
