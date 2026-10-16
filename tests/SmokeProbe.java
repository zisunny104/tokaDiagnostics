import cn.nukkit.plugin.PluginBase;
import java.util.*;
public class SmokeProbe extends PluginBase {
 @Override public void onEnable() {
  try {
   Object plugin=getServer().getPluginManager().getPlugin("tokaDiagnostics");
   if(plugin==null || !((PluginBase)plugin).isEnabled()) throw new Exception("not enabled");
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
