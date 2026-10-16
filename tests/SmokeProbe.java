import cn.nukkit.plugin.PluginBase;
import java.util.*;
public class SmokeProbe extends PluginBase {
 @Override public void onEnable() {
  try {
   Object plugin=getServer().getPluginManager().getPlugin("tokaDiagnostics");
   if(plugin==null || !((PluginBase)plugin).isEnabled()) throw new Exception("not enabled");
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
   var finishPending=plugin.getClass().getDeclaredMethod("finish",UUID.class,String.class);finishPending.setAccessible(true);finishPending.invoke(plugin,null,"QUIT");
   getLogger().info("PRELOGIN_NULL_UUID_PASS");
   Class<?> type=Class.forName("dev.toka.diagnostics.Trace",true,plugin.getClass().getClassLoader());
   var constructor=type.getDeclaredConstructor(int.class,long.class);constructor.setAccessible(true);
   Object trace=constructor.newInstance(2,System.nanoTime()+10000000000L);
   var field=plugin.getClass().getDeclaredField("traces");field.setAccessible(true);
   UUID id=UUID.randomUUID();((Map)field.get(plugin)).put(id,trace);
   var finish=plugin.getClass().getDeclaredMethod("finish",UUID.class,String.class);finish.setAccessible(true);finish.invoke(plugin,id,"SMOKE");
   getLogger().info("DIAGNOSTICS_SMOKE_PASS");
  } catch(Exception e) {getLogger().error("DIAGNOSTICS_SMOKE_FAIL",e);}
  getServer().getScheduler().scheduleDelayedTask(this,()->getServer().shutdown(),40);
 }
}
